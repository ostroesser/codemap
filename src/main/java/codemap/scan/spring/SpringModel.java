package codemap.scan.spring;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import codemap.db.BatchInserter;
import codemap.scan.bytecode.Json;
import codemap.scan.bytecode.TypeNames;

/**
 * Service-scoped Spring facts, recomputed for the whole workspace after each scan: services and
 * their module closure, beans, resolved injections, endpoints, listeners, and resolved outbound
 * targets. Static rows only; runtime rows (v2) are left untouched.
 */
public final class SpringModel {

    private static final String BOOT_APP = Meta.SPRING + "boot.autoconfigure.SpringBootApplication";
    private static final String COMPONENT_SCAN = Meta.SPRING + "context.annotation.ComponentScan";
    private static final String CONTROLLER = Meta.SPRING + "stereotype.Controller";

    private static final Map<String, String> LISTENERS = Map.of(
            Meta.SPRING + "kafka.annotation.KafkaListener", "kafka",
            Meta.SPRING + "jms.annotation.JmsListener", "jms",
            Meta.SPRING + "amqp.rabbit.annotation.RabbitListener", "rabbit",
            Meta.SPRING + "context.event.EventListener", "event",
            Meta.SPRING + "scheduling.annotation.Scheduled", "scheduled");

    /** Warning kinds produced here; cleared before each rebuild. */
    static final List<String> WARNING_KINDS = List.of("unresolved_placeholder", "profile_dependent");

    private final Connection conn;
    private final Meta meta;
    private final List<String[]> warnings = new ArrayList<>(); // repo, module, kind, message
    private final Set<String> warned = new HashSet<>();

    private record Service(String name, String repo, String module, String mainClass, List<String> scanPackages) {
    }

    private record Bean(String name, String classFqn, String declaredBy, String declaringClass, String factoryMethod,
                        String scope, boolean primary, String conditions, String module) {
    }

    private SpringModel(Connection conn, Meta meta) {
        this.conn = conn;
        this.meta = meta;
    }

    public static void rebuild(Connection conn) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            new SpringModel(conn, Meta.load(conn)).run();
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    private void run() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM services");
            st.executeUpdate("DELETE FROM service_modules");
            st.executeUpdate("UPDATE modules SET is_service = 0");
            for (String t : List.of("beans", "injections", "endpoints", "listeners")) {
                st.executeUpdate("DELETE FROM " + t + " WHERE source = 'static'");
            }
            st.executeUpdate("DELETE FROM scan_warnings WHERE kind IN ('" + String.join("', '", WARNING_KINDS) + "')");
        }
        List<Service> services = findServices();
        Map<String, List<String>> serviceModules = new LinkedHashMap<>();
        for (Service s : services) {
            serviceModules.put(s.name(), moduleClosure(s));
        }
        writeServices(services, serviceModules);

        Map<String, Placeholders> resolvers = new HashMap<>();
        for (Service s : services) {
            resolvers.put(s.name(), resolver(serviceModules.get(s.name())));
            List<Bean> beans = beans(s, serviceModules.get(s.name()));
            writeBeans(s, beans);
            injections(s, beans, resolvers.get(s.name()));
            endpoints(s, beans, resolvers.get(s.name()));
            listeners(s, beans, resolvers.get(s.name()));
        }
        resolveOutbound(services, serviceModules, resolvers);
        try (var w = new BatchInserter(conn, "scan_warnings", "repo", "module", "kind", "message")) {
            for (String[] x : warnings) {
                w.add((Object[]) x);
            }
        }
    }

    // ── services ───────────────────────────────────────────────────────

    private List<Service> findServices() throws SQLException {
        List<Service> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        String sql = "SELECT a.target, c.repo, c.module, a.annotation_fqn, a.attributes FROM annotations a"
                + " JOIN classes c ON c.fqn = a.target AND c.is_external = 0"
                + " WHERE a.target_kind = 'class' ORDER BY c.repo, c.module, a.target";
        Map<String, Map<String, Object>> componentScans = new HashMap<>();
        List<String[]> candidates = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String ann = rs.getString(4);
                if (ann.equals(COMPONENT_SCAN)) {
                    componentScans.put(rs.getString(1), Json.parseObject(rs.getString(5)));
                } else if (meta.implies(ann, BOOT_APP)) {
                    candidates.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(5)});
                }
            }
        }
        for (String[] c : candidates) {
            String mainClass = c[0];
            Map<String, Object> attrs = Json.parseObject(c[3]);
            List<String> packages = new ArrayList<>(Meta.strings(attrs, "scanBasePackages"));
            Meta.strings(attrs, "scanBasePackageClasses").forEach(cl -> packages.add(TypeNames.packageOf(cl)));
            Map<String, Object> scan = componentScans.get(mainClass);
            if (scan != null) {
                packages.addAll(Meta.strings(scan, "basePackages", "value"));
                Meta.strings(scan, "basePackageClasses").forEach(cl -> packages.add(TypeNames.packageOf(cl)));
            }
            if (packages.isEmpty()) {
                packages.add(TypeNames.packageOf(mainClass));
            }
            String name = applicationName(c[2]);
            if (name == null || !names.add(name)) {
                name = names.contains(c[2]) ? c[2] + "#" + TypeNames.simple(mainClass) : c[2];
                names.add(name);
            }
            result.add(new Service(name, c[1], c[2], mainClass, List.copyOf(new LinkedHashSet<>(packages))));
        }
        return result;
    }

    private String applicationName(String module) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM config_properties WHERE module = ?"
                + " AND profile = '(default)' AND key = 'spring.application.name' AND source = 'static' LIMIT 1")) {
            ps.setString(1, module);
            try (ResultSet rs = ps.executeQuery()) {
                String v = rs.next() ? rs.getString(1) : null;
                return v == null || v.isBlank() || v.contains("${") ? null : v.strip();
            }
        }
    }

    /** The service module plus its internal compile/runtime dependencies, nearest first. */
    private List<String> moduleClosure(Service s) throws SQLException {
        Map<String, List<String>> edges = new HashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT module, internal_module"
                + " FROM maven_dependencies WHERE internal_module IS NOT NULL"
                + " AND COALESCE(scope, 'compile') NOT IN ('test', 'import', 'system')")) {
            while (rs.next()) {
                edges.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
            }
        }
        LinkedHashMap<String, Integer> depth = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>(List.of(s.module()));
        depth.put(s.module(), 0);
        while (!queue.isEmpty()) {
            String m = queue.poll();
            for (String next : edges.getOrDefault(m, List.of())) {
                if (!depth.containsKey(next)) {
                    depth.put(next, depth.get(m) + 1);
                    queue.add(next);
                }
            }
        }
        moduleDepths.put(s.name(), depth);
        return List.copyOf(depth.keySet());
    }

    private final Map<String, Map<String, Integer>> moduleDepths = new HashMap<>();

    private void writeServices(List<Service> services, Map<String, List<String>> modules) throws SQLException {
        try (var sv = new BatchInserter(conn, "services", "service", "repo", "module", "main_class", "scan_packages");
             var sm = new BatchInserter(conn, "service_modules", "service", "module", "depth");
             PreparedStatement flag = conn.prepareStatement("UPDATE modules SET is_service = 1 WHERE module = ?")) {
            for (Service s : services) {
                sv.add(s.name(), s.repo(), s.module(), s.mainClass(), String.join(",", s.scanPackages()));
                for (String m : modules.get(s.name())) {
                    sm.add(s.name(), m, moduleDepths.get(s.name()).get(m));
                }
                flag.setString(1, s.module());
                flag.executeUpdate();
            }
        }
    }

    private Placeholders resolver(List<String> modules) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        for (String m : modules) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT profile, key, value FROM config_properties"
                    + " WHERE module = ? AND source = 'static' ORDER BY file")) {
                ps.setString(1, m);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
                    }
                }
            }
        }
        return Placeholders.fromRows(rows);
    }

    private String resolve(Placeholders p, String expr, String repo, String module, String what) {
        if (expr == null) {
            return null;
        }
        Placeholders.Result r = p.resolve(expr);
        for (String key : r.unresolved()) {
            warn(repo, module, "unresolved_placeholder", "${" + key + "} in " + what);
        }
        for (String key : r.profileDependent()) {
            warn(repo, module, "profile_dependent", "${" + key + "} in " + what + " differs by profile;"
                    + " stored value is for no active profile (see config_effective)");
        }
        return r.value();
    }

    private void warn(String repo, String module, String kind, String message) {
        if (warned.add(kind + "|" + module + "|" + message)) {
            warnings.add(new String[]{repo, module, kind, message});
        }
    }

    // ── beans ──────────────────────────────────────────────────────────

    private List<Bean> beans(Service s, List<String> modules) throws SQLException {
        List<Bean> all = new ArrayList<>();
        String placeholders = String.join(", ", modules.stream().map(m -> "?").toList());
        try (PreparedStatement ps = conn.prepareStatement("SELECT bean_name, class_fqn, declared_by, declaring_class,"
                + " factory_method, scope, is_primary, conditions, module FROM bean_definitions"
                + " WHERE module IN (" + placeholders + ")")) {
            for (int i = 0; i < modules.size(); i++) {
                ps.setString(i + 1, modules.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    all.add(new Bean(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getInt(7) == 1, rs.getString(8), rs.getString(9)));
                }
            }
        }
        // Scanned classes and repositories count when under a scan package; auto-configurations
        // always; @Bean methods when their declaring class is itself a bean of this service.
        Set<String> declaring = new HashSet<>();
        List<Bean> result = new ArrayList<>();
        for (Bean b : all) {
            boolean in = switch (b.declaredBy()) {
                case "auto_config" -> true;
                case "scan", "data_repository" -> inPackages(b.declaringClass(), s.scanPackages());
                default -> false;
            };
            if (in) {
                result.add(b);
                declaring.add(b.declaringClass());
            }
        }
        for (Bean b : all) {
            if (b.declaredBy().equals("bean_method") && declaring.contains(b.declaringClass())) {
                result.add(b);
            }
        }
        return result;
    }

    private static boolean inPackages(String fqn, List<String> packages) {
        for (String p : packages) {
            if (fqn.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }

    private void writeBeans(Service s, List<Bean> beans) throws SQLException {
        try (var out = new BatchInserter(conn, "beans", "service", "bean_name", "class_fqn", "repo", "module", "role",
                "declared_by", "factory_method", "scope", "is_primary", "conditions", "source");
             PreparedStatement cls = conn.prepareStatement("SELECT repo, module, role FROM classes WHERE fqn = ?")) {
            for (Bean b : beans) {
                String repo = null;
                String module = b.module();
                String role = null;
                cls.setString(1, b.classFqn());
                try (ResultSet rs = cls.executeQuery()) {
                    if (rs.next()) {
                        repo = rs.getString(1);
                        module = rs.getString(2) != null ? rs.getString(2) : module;
                        role = rs.getString(3);
                    }
                }
                if (repo == null) {
                    repo = repoOf(b.module());
                }
                out.add(s.name(), b.name(), b.classFqn(), repo, module, role, b.declaredBy(), b.factoryMethod(),
                        b.scope(), b.primary(), b.conditions(), "static");
            }
        }
    }

    private final Map<String, String> repoOfModule = new HashMap<>();

    private String repoOf(String module) throws SQLException {
        if (!repoOfModule.containsKey(module)) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT repo FROM modules WHERE module = ?")) {
                ps.setString(1, module);
                try (ResultSet rs = ps.executeQuery()) {
                    repoOfModule.put(module, rs.next() ? rs.getString(1) : null);
                }
            }
        }
        return repoOfModule.get(module);
    }

    // ── injections ─────────────────────────────────────────────────────

    private void injections(Service s, List<Bean> beans, Placeholders resolver) throws SQLException {
        Set<String> consumers = new HashSet<>();
        for (Bean b : beans) {
            consumers.add(b.declaringClass());
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT consumer_class, point, member, required_type, required_fqn,"
                     + " is_collection, qualifier FROM injection_points WHERE value_expr IS NULL AND required_fqn IS NOT NULL");
             var out = new BatchInserter(conn, "injections", "service", "consumer_class", "point", "member",
                     "required_type", "qualifier", "resolved_class", "candidates", "source")) {
            while (rs.next()) {
                String consumer = rs.getString(1);
                if (!consumers.contains(consumer)) {
                    continue;
                }
                String point = rs.getString(2);
                String member = rs.getString(3);
                String required = rs.getString(5);
                boolean collection = rs.getInt(6) == 1;
                String qualifier = rs.getString(7);
                List<Bean> matches = beans.stream()
                        .filter(b -> b.classFqn() != null && meta.isSubtype(b.classFqn(), required)).toList();
                String resolved = null;
                if (!collection) {
                    Bean chosen = narrow(matches, qualifier, member);
                    resolved = chosen == null ? null : chosen.classFqn();
                }
                out.add(s.name(), consumer, point, member, rs.getString(4), qualifier, resolved, matches.size(), "static");
            }
        }
    }

    /** Spring's choice among candidates: qualifier, active-profile filter, @Primary, then name. */
    private static Bean narrow(List<Bean> candidates, String qualifier, String member) {
        List<Bean> c = candidates;
        if (qualifier != null) {
            c = c.stream().filter(b -> b.name().equals(qualifier)).toList();
        }
        if (c.size() > 1) {
            List<Bean> active = c.stream().filter(b -> activeWithoutProfiles(b.conditions())).toList();
            if (!active.isEmpty()) {
                c = active;
            }
        }
        if (c.size() > 1) {
            List<Bean> primary = c.stream().filter(Bean::primary).toList();
            if (primary.size() == 1) {
                c = primary;
            }
        }
        if (c.size() > 1 && member != null) {
            List<Bean> byName = c.stream().filter(b -> b.name().equals(member)).toList();
            if (byName.size() == 1) {
                c = byName;
            }
        }
        return c.size() == 1 ? c.get(0) : null;
    }

    /** False for beans guarded by @Profile that needs a profile to be active. */
    private static boolean activeWithoutProfiles(String conditions) {
        if (conditions == null) {
            return true;
        }
        for (String part : conditions.split("; ")) {
            if (part.startsWith("profile=")) {
                boolean anyPositive = false;
                for (String p : part.substring(8).split("\\|")) {
                    if (p.startsWith("!") || p.equals("default")) {
                        return true;
                    }
                    anyPositive = true;
                }
                if (anyPositive) {
                    return false;
                }
            }
        }
        return true;
    }

    // ── endpoints ──────────────────────────────────────────────────────

    private void endpoints(Service s, List<Bean> beans, Placeholders resolver) throws SQLException {
        String contextPath = resolver.lookup("server.servlet.context-path");
        String servletPath = resolver.lookup("spring.mvc.servlet.path");
        String prefix = Paths.join(contextPath == null ? "" : contextPath, servletPath == null ? "" : servletPath);
        if (prefix.equals("/")) {
            prefix = "";
        }
        Set<String> controllers = new LinkedHashSet<>();
        for (Bean b : beans) {
            if (b.declaredBy().equals("scan") || b.declaredBy().equals("auto_config")) {
                controllers.add(b.classFqn());
            }
        }
        try (var out = new BatchInserter(conn, "endpoints", "service", "http_method", "path", "path_key", "class_fqn",
                "method_id", "produces", "consumes", "path_expr", "source")) {
            for (String cls : controllers) {
                List<String[]> classAnnotations = annotations(cls, "class");
                if (classAnnotations.stream().noneMatch(a -> meta.implies(a[1], CONTROLLER))) {
                    continue;
                }
                Map<String, Object> classMapping = mapping(classAnnotations);
                List<String> basePaths = classMapping == null ? List.of("")
                        : orDefault(Meta.strings(classMapping, "value", "path"), "");
                for (String[] a : annotations(cls, "method")) {
                    if (!meta.implies(a[1], Meta.REQUEST_MAPPING)) {
                        continue;
                    }
                    Map<String, Object> attrs = Json.parseObject(a[2]);
                    List<String> methods = TypeNames.simple(a[1]).equals("RequestMapping")
                            ? Meta.strings(attrs, "method") : verbs(a[1]);
                    for (String base : basePaths) {
                        for (String path : orDefault(Meta.strings(attrs, "value", "path"), "")) {
                            String expr = Paths.join(base, path);
                            String resolved = Paths.join(prefix, resolve(resolver, expr, s.repo(), s.module(),
                                    "mapping of " + a[0]));
                            for (String method : methods.isEmpty() ? java.util.Collections.<String>singletonList(null) : methods) {
                                out.add(s.name(), method, resolved, Paths.key(resolved), cls, a[0],
                                        joined(attrs, classMapping, "produces"), joined(attrs, classMapping, "consumes"),
                                        expr, "static");
                            }
                        }
                    }
                }
            }
        }
    }

    private Map<String, Object> mapping(List<String[]> classAnnotations) {
        for (String[] a : classAnnotations) {
            if (meta.implies(a[1], Meta.REQUEST_MAPPING)) {
                return Json.parseObject(a[2]);
            }
        }
        return null;
    }

    private static List<String> verbs(String annotationFqn) {
        String simple = TypeNames.simple(annotationFqn);
        String verb = simple.endsWith("Mapping") ? simple.substring(0, simple.length() - 7).toUpperCase() : "";
        return Set.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(verb) ? List.of(verb) : List.of();
    }

    private static List<String> orDefault(List<String> values, String fallback) {
        return values.isEmpty() ? List.of(fallback) : values;
    }

    private static String joined(Map<String, Object> method, Map<String, Object> type, String attr) {
        List<String> v = Meta.strings(method, attr);
        if (v.isEmpty() && type != null) {
            v = Meta.strings(type, attr);
        }
        return v.isEmpty() ? null : String.join(",", v);
    }

    /** (target, annotation_fqn, attributes) rows of a class, for class or method targets. */
    private List<String[]> annotations(String classFqn, String targetKind) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT target, annotation_fqn, attributes FROM annotations"
                + " WHERE class_fqn = ? AND target_kind = ? ORDER BY rowid")) {
            ps.setString(1, classFqn);
            ps.setString(2, targetKind);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
                }
            }
        }
        return rows;
    }

    // ── listeners ──────────────────────────────────────────────────────

    private void listeners(Service s, List<Bean> beans, Placeholders resolver) throws SQLException {
        Set<String> classes = new LinkedHashSet<>();
        for (Bean b : beans) {
            if (!b.declaredBy().equals("bean_method") && b.classFqn() != null) {
                classes.add(b.classFqn());
            }
        }
        try (var out = new BatchInserter(conn, "listeners", "service", "kind", "destination", "destination_expr",
                "group_id", "class_fqn", "method_id", "payload_type", "schedule", "source")) {
            for (String cls : classes) {
                List<String[]> classLevel = annotations(cls, "class");
                for (String[] a : annotations(cls, "method")) {
                    String kind = listenerKind(a[1]);
                    String methodId = a[0];
                    if (kind == null && TypeNames.simple(a[1]).equals("KafkaHandler")) {
                        String[] onClass = classLevel.stream()
                                .filter(c -> "kafka".equals(listenerKind(c[1]))).findFirst().orElse(null);
                        if (onClass != null) {
                            listener(out, s, resolver, "kafka", Json.parseObject(onClass[2]), cls, methodId);
                        }
                        continue;
                    }
                    if (kind != null) {
                        listener(out, s, resolver, kind, Json.parseObject(a[2]), cls, methodId);
                    }
                }
            }
        }
    }

    private String listenerKind(String annotationFqn) {
        for (var e : LISTENERS.entrySet()) {
            if (meta.implies(annotationFqn, e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private void listener(BatchInserter out, Service s, Placeholders resolver, String kind, Map<String, Object> at,
                          String cls, String methodId) throws SQLException {
        String payload = firstParameter(methodId);
        String what = "listener " + methodId;
        switch (kind) {
            case "kafka", "jms", "rabbit" -> {
                List<String> destinations = switch (kind) {
                    case "kafka" -> Meta.strings(at, "topics", "topicPattern");
                    case "jms" -> Meta.strings(at, "destination");
                    default -> Meta.strings(at, "queues");
                };
                String group = Meta.string(at, kind.equals("kafka") ? "groupId" : "subscription", "id");
                for (String d : destinations.isEmpty() ? List.of("") : destinations) {
                    String expr = d.isEmpty() ? null : d;
                    out.add(s.name(), kind, resolve(resolver, expr, s.repo(), s.module(), what), expr,
                            resolve(resolver, group, s.repo(), s.module(), what), cls, methodId, payload, null, "static");
                }
            }
            case "event" -> {
                List<String> classes = Meta.strings(at, "classes", "value");
                String type = classes.isEmpty() ? payload : TypeNames.simple(classes.get(0));
                out.add(s.name(), kind, null, null, null, cls, methodId, type,
                        Meta.string(at, "condition") == null ? null : "if " + Meta.string(at, "condition"), "static");
            }
            default -> {
                List<String> parts = new ArrayList<>();
                for (String attr : List.of("cron", "fixedRate", "fixedRateString", "fixedDelay", "fixedDelayString",
                        "initialDelay", "initialDelayString")) {
                    Object v = at.get(attr);
                    if (v != null && !String.valueOf(v).isEmpty() && !"-1".equals(String.valueOf(v))) {
                        parts.add(attr.replace("String", "") + "="
                                + resolve(resolver, String.valueOf(v), s.repo(), s.module(), what));
                    }
                }
                out.add(s.name(), kind, null, null, null, cls, methodId, null, String.join(", ", parts), "static");
            }
        }
    }

    private static String firstParameter(String methodId) {
        int open = methodId.indexOf('(');
        String params = methodId.substring(open + 1, methodId.length() - 1);
        if (params.isEmpty()) {
            return null;
        }
        int comma = params.indexOf(',');
        return comma < 0 ? params : params.substring(0, comma);
    }

    // ── outbound ───────────────────────────────────────────────────────

    private void resolveOutbound(List<Service> services, Map<String, List<String>> serviceModules,
                                 Map<String, Placeholders> resolvers) throws SQLException {
        Map<String, String> serviceOfModule = new HashMap<>();
        for (Service s : services) {
            for (String m : serviceModules.get(s.name())) {
                serviceOfModule.putIfAbsent(m, s.name());
            }
        }
        List<Object[]> updates = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT rowid, repo, module, kind, target_expr, method_id FROM outbound")) {
            while (rs.next()) {
                String module = rs.getString(3);
                String kind = rs.getString(4);
                String expr = rs.getString(5);
                String service = serviceOfModule.get(module);
                Placeholders p = service != null ? resolvers.get(service) : resolver(List.of(module));
                String target = resolve(p, expr, rs.getString(2), module, "outbound " + rs.getString(6));
                if (kind.equals("stream") && target != null) {
                    String bound = p.lookup("spring.cloud.stream.bindings." + target + ".destination");
                    if (bound != null) {
                        target = bound;
                    }
                }
                boolean http = Set.of("http_exchange", "feign", "rest_client", "web_client", "rest_template").contains(kind);
                updates.add(new Object[]{target, http ? Paths.key(target) : null, rs.getLong(1)});
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE outbound SET target = ?, path_key = ? WHERE rowid = ?")) {
            for (Object[] u : updates) {
                ps.setObject(1, u[0]);
                ps.setObject(2, u[1]);
                ps.setObject(3, u[2]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
