package codemap.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import codemap.db.BatchInserter;
import codemap.scan.bytecode.Json;
import codemap.scan.bytecode.TypeNames;
import codemap.scan.spring.Paths;

/**
 * Loads actuator JSON dumps of one running service ({@code beans.json}, {@code mappings.json},
 * {@code conditions.json}, {@code configprops.json}, {@code scheduledtasks.json}) into the fact
 * tables with {@code source = 'runtime'}. Each file present replaces that service's previous
 * runtime rows of the same kind; missing files leave them alone.
 */
public final class ActuatorImporter {

    public record Report(Map<String, Integer> rowsByFile, List<String> notes) {
    }

    private static final Pattern RESOURCE_CLASS = Pattern.compile("\\[([^\\]]+)\\.class]");
    private static final Pattern DEFINED_IN = Pattern.compile("^([\\w.$]+) defined in ");

    private final Connection conn;
    private final String service;
    private final String repo;
    private final String module;
    private final List<String> notes = new ArrayList<>();

    public ActuatorImporter(Connection conn, String service) throws SQLException {
        this.conn = conn;
        this.service = service;
        String[] s = one("SELECT repo, module FROM services WHERE service = ?", service);
        if (s == null) {
            notes.add("service '" + service + "' is not in the static scan; rows are imported without repo/module");
        }
        this.repo = s == null ? null : s[0];
        this.module = s == null ? null : s[1];
    }

    public Report importDir(Path dir) throws IOException, SQLException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("Not a directory: " + dir);
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            Map<String, Object> configprops = read(dir, "configprops");
            Map<String, Object> beans = read(dir, "beans");
            Map<String, Object> mappings = read(dir, "mappings");
            Map<String, Object> conditions = read(dir, "conditions");
            Map<String, Object> scheduled = read(dir, "scheduledtasks");
            if (configprops != null) {
                counts.put("configprops.json", configprops(configprops));
            }
            if (beans != null) {
                counts.put("beans.json", beans(beans));
            }
            if (mappings != null) {
                counts.put("mappings.json", mappings(mappings, contextPath()));
            }
            if (conditions != null) {
                counts.put("conditions.json", conditions(conditions));
            }
            if (scheduled != null) {
                counts.put("scheduledtasks.json", scheduledTasks(scheduled));
            }
            conn.commit();
        } catch (SQLException | IOException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
        if (counts.isEmpty()) {
            notes.add("no actuator files found in " + dir
                    + " (expected beans.json, mappings.json, conditions.json, configprops.json, scheduledtasks.json)");
        }
        return new Report(counts, notes);
    }

    private static Map<String, Object> read(Path dir, String endpoint) throws IOException {
        Path file = dir.resolve(endpoint + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IOException(file + " is not valid JSON: " + e.getMessage());
        }
    }

    /** Every application context of an actuator response (child and parent contexts). */
    private static List<Map<String, Object>> contexts(Map<String, Object> root) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object ctx : map(root.get("contexts")).values()) {
            result.add(map(ctx));
        }
        return result;
    }

    // ── beans ──────────────────────────────────────────────────────────

    private int beans(Map<String, Object> root) throws SQLException {
        delete("DELETE FROM beans WHERE service = ? AND source = 'runtime'");
        delete("DELETE FROM injections WHERE service = ? AND source = 'runtime'");
        Map<String, String> typeOf = new LinkedHashMap<>();
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        for (Map<String, Object> ctx : contexts(root)) {
            for (var e : map(ctx.get("beans")).entrySet()) {
                Map<String, Object> bean = map(e.getValue());
                all.put(e.getKey(), bean);
                typeOf.put(e.getKey(), beanClass(str(bean.get("type")), str(bean.get("resource"))));
            }
        }
        int rows = 0;
        try (var beans = new BatchInserter(conn, "beans", "service", "bean_name", "class_fqn", "repo", "module", "role",
                "declared_by", "factory_method", "scope", "is_primary", "conditions", "source");
             var injections = new BatchInserter(conn, "injections", "service", "consumer_class", "point", "member",
                     "required_type", "qualifier", "resolved_class", "candidates", "source")) {
            for (var e : all.entrySet()) {
                String cls = typeOf.get(e.getKey());
                String resource = str(e.getValue().get("resource"));
                String[] owner = cls == null ? null : one("SELECT repo, module, role FROM classes"
                        + " WHERE fqn = ? AND is_external = 0", cls);
                beans.add(service, e.getKey(), cls, owner == null ? null : owner[0], owner == null ? null : owner[1],
                        owner == null ? null : owner[2], declaredBy(resource, owner != null), null,
                        str(e.getValue().get("scope")), null, null, "runtime");
                rows++;
                for (Object dep : list(e.getValue().get("dependencies"))) {
                    String depName = String.valueOf(dep);
                    if (cls != null) {
                        injections.add(service, cls, null, depName, null, null, typeOf.get(depName), 1, "runtime");
                    }
                }
            }
        }
        return rows;
    }

    /** Bean class without CGLIB suffixes; for JDK proxies, the interface named in the resource. */
    static String beanClass(String type, String resource) {
        if (type != null && (type.startsWith("jdk.proxy") || type.contains("$Proxy"))) {
            if (resource != null) {
                Matcher m = DEFINED_IN.matcher(resource);
                if (m.find()) {
                    return m.group(1);
                }
            }
            return type;
        }
        if (type == null) {
            return null;
        }
        int cglib = type.indexOf("$$");
        return cglib > 0 ? type.substring(0, cglib) : type;
    }

    private static String declaredBy(String resource, boolean internalClass) {
        if (resource == null) {
            return "runtime";
        }
        if (resource.contains("@EnableJpaRepositories") || resource.contains("Repositories")) {
            return "data_repository";
        }
        Matcher m = RESOURCE_CLASS.matcher(resource);
        if (resource.startsWith("class path resource") && m.find()) {
            String declaring = m.group(1).replace('/', '.');
            return declaring.startsWith("org.springframework.") || !internalClass && declaring.contains("autoconfigure")
                    ? "auto_config" : "bean_method";
        }
        if (resource.startsWith("file [") || resource.startsWith("URL [")) {
            return "scan";
        }
        return "runtime";
    }

    // ── mappings ───────────────────────────────────────────────────────

    private int mappings(Map<String, Object> root, String contextPath) throws SQLException {
        delete("DELETE FROM endpoints WHERE service = ? AND source = 'runtime'");
        int rows = 0;
        try (var out = new BatchInserter(conn, "endpoints", "service", "http_method", "path", "path_key", "class_fqn",
                "method_id", "produces", "consumes", "path_expr", "source")) {
            for (Map<String, Object> ctx : contexts(root)) {
                Map<String, Object> mappings = map(ctx.get("mappings"));
                for (String kind : List.of("dispatcherServlets", "dispatcherHandlers")) {
                    for (Object handlers : map(mappings.get(kind)).values()) {
                        for (Object h : list(handlers)) {
                            rows += mapping(out, map(h), contextPath);
                        }
                    }
                }
            }
        }
        return rows;
    }

    private int mapping(BatchInserter out, Map<String, Object> m, String contextPath) throws SQLException {
        Map<String, Object> details = map(m.get("details"));
        Map<String, Object> handler = map(details.get("handlerMethod"));
        Map<String, Object> conditions = map(details.get("requestMappingConditions"));
        if (handler.isEmpty() || conditions.isEmpty()) {
            return 0; // resource handlers, router functions
        }
        String cls = str(handler.get("className"));
        String methodId = TypeNames.methodId(cls, str(handler.get("name")), str(handler.get("descriptor")));
        List<Object> methods = list(conditions.get("methods"));
        int rows = 0;
        for (Object pattern : list(conditions.get("patterns"))) {
            String path = Paths.join(contextPath, String.valueOf(pattern));
            for (Object method : methods.isEmpty() ? java.util.Collections.singletonList(null) : methods) {
                out.add(service, method == null ? null : String.valueOf(method), path, Paths.key(path), cls, methodId,
                        mediaTypes(conditions.get("produces")), mediaTypes(conditions.get("consumes")),
                        str(m.get("predicate")), "runtime");
                rows++;
            }
        }
        return rows;
    }

    private static String mediaTypes(Object value) {
        List<String> types = new ArrayList<>();
        for (Object o : list(value)) {
            Map<String, Object> mt = map(o);
            if (!Boolean.TRUE.equals(mt.get("negated")) && mt.get("mediaType") != null) {
                types.add(String.valueOf(mt.get("mediaType")));
            }
        }
        return types.isEmpty() ? null : String.join(",", types);
    }

    // ── conditions ─────────────────────────────────────────────────────

    private int conditions(Map<String, Object> root) throws SQLException {
        delete("DELETE FROM conditions WHERE service = ? AND source = 'runtime'");
        int rows = 0;
        try (var out = new BatchInserter(conn, "conditions", "service", "condition_source", "outcome", "message",
                "source")) {
            for (Map<String, Object> ctx : contexts(root)) {
                for (var e : map(ctx.get("positiveMatches")).entrySet()) {
                    out.add(service, e.getKey(), "positive", messages(list(e.getValue())), "runtime");
                    rows++;
                }
                for (var e : map(ctx.get("negativeMatches")).entrySet()) {
                    out.add(service, e.getKey(), "negative",
                            messages(list(map(e.getValue()).get("notMatched"))), "runtime");
                    rows++;
                }
                for (Object c : list(ctx.get("unconditionalClasses"))) {
                    out.add(service, String.valueOf(c), "unconditional", null, "runtime");
                    rows++;
                }
            }
        }
        return rows;
    }

    private static String messages(List<Object> outcomes) {
        List<String> parts = new ArrayList<>();
        for (Object o : outcomes) {
            Map<String, Object> m = map(o);
            parts.add(str(m.get("condition")) + ": " + str(m.get("message")));
        }
        return String.join("; ", parts);
    }

    // ── configprops ────────────────────────────────────────────────────

    private int configprops(Map<String, Object> root) throws SQLException {
        delete("DELETE FROM config_properties WHERE source = 'runtime' AND module IS ? AND file = '"
                + "actuator/configprops" + "'", module);
        Map<String, String> values = new LinkedHashMap<>();
        for (Map<String, Object> ctx : contexts(root)) {
            for (Object bean : map(ctx.get("beans")).values()) {
                Map<String, Object> b = map(bean);
                flatten(str(b.get("prefix")), b.get("properties"), values);
            }
        }
        try (var out = new BatchInserter(conn, "config_properties", "repo", "module", "file", "profile", "key", "value",
                "source")) {
            for (var e : values.entrySet()) {
                out.add(repo, module, "actuator/configprops", "(runtime)", e.getKey(), e.getValue(), "runtime");
            }
        }
        runtimeConfig = values;
        return values.size();
    }

    /** Runtime config read from configprops.json in this import, used for the context path. */
    private Map<String, String> runtimeConfig = Map.of();

    private String contextPath() {
        String path = runtimeConfig.get("server.servlet.context-path");
        return path == null || path.equals("null") ? "" : path;
    }

    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                String key = kebab(String.valueOf(e.getKey()));
                flatten(prefix == null || prefix.isEmpty() ? key : prefix + "." + key, e.getValue(), out);
            }
        } else if (node instanceof List<?> l) {
            if (l.stream().allMatch(x -> !(x instanceof Map<?, ?>) && !(x instanceof List<?>))) {
                out.put(prefix, String.join(",", l.stream().map(String::valueOf).toList()));
            } else {
                for (int i = 0; i < l.size(); i++) {
                    flatten(prefix + "[" + i + "]", l.get(i), out);
                }
            }
        } else if (node != null && prefix != null) {
            out.put(prefix, String.valueOf(node));
        }
    }

    /** bootstrapServers → bootstrap-servers, the canonical form used in yml. */
    static String kebab(String key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && key.charAt(i - 1) != '.' && key.charAt(i - 1) != '[') {
                sb.append('-').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ── scheduled tasks ────────────────────────────────────────────────

    private int scheduledTasks(Map<String, Object> root) throws SQLException {
        delete("DELETE FROM listeners WHERE service = ? AND source = 'runtime' AND kind = 'scheduled'");
        int rows = 0;
        try (var out = new BatchInserter(conn, "listeners", "service", "kind", "destination", "destination_expr",
                "group_id", "class_fqn", "method_id", "payload_type", "schedule", "source")) {
            for (String type : List.of("cron", "fixedDelay", "fixedRate", "custom")) {
                for (Object t : list(root.get(type))) {
                    Map<String, Object> task = map(t);
                    String target = str(map(task.get("runnable")).get("target"));
                    if (target == null) {
                        continue;
                    }
                    int dot = target.lastIndexOf('.');
                    String cls = dot > 0 ? target.substring(0, dot) : target;
                    String name = dot > 0 ? target.substring(dot + 1) : null;
                    String[] method = name == null ? null : one("SELECT method_id FROM methods"
                            + " WHERE class_fqn = ? AND name = ? ORDER BY method_id LIMIT 1", cls, name);
                    String schedule = switch (type) {
                        case "cron" -> "cron=" + str(task.get("expression"));
                        case "custom" -> "trigger=" + str(task.get("trigger"));
                        default -> type + "=" + str(task.get("interval"))
                                + (task.get("initialDelay") == null ? "" : ", initialDelay=" + task.get("initialDelay"));
                    };
                    out.add(service, "scheduled", null, null, null, cls,
                            method != null ? method[0] : cls + "#" + name + "()", null, schedule, "runtime");
                    rows++;
                }
            }
        }
        return rows;
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void delete(String sql, Object... extra) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (extra.length == 0) {
                ps.setString(1, service);
            } else {
                for (int i = 0; i < extra.length; i++) {
                    ps.setObject(i + 1, extra[i]);
                }
            }
            ps.executeUpdate();
        }
    }

    private String[] one(String sql, String... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String[] row = new String[rs.getMetaData().getColumnCount()];
                for (int i = 0; i < row.length; i++) {
                    row[i] = rs.getString(i + 1);
                }
                return row;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
