package codemap.scan.spring;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Type;

import codemap.db.BatchInserter;
import codemap.scan.LocalConfig;
import codemap.scan.bytecode.ClassFact;
import codemap.scan.bytecode.ClassFact.AnnotationFact;
import codemap.scan.bytecode.ClassFact.CallFact;
import codemap.scan.bytecode.ClassFact.FieldFact;
import codemap.scan.bytecode.ClassFact.MethodFact;
import codemap.scan.bytecode.TypeNames;

/**
 * Module-scoped Spring facts that need method bodies or descriptors: bean declarations,
 * injection points, outbound calls (raw expressions), JPA entities and Spring Data repositories.
 * Service-scoped facts are derived later by {@link SpringModel}.
 */
public final class SpringScanner {

    static final String AUTO_CONFIG_IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private static final Set<String> COLLECTIONS = Set.of("java.util.List", "java.util.Set", "java.util.Collection",
            "java.util.Map", "java.util.Optional", "org.springframework.beans.factory.ObjectProvider",
            "jakarta.inject.Provider", "java.util.SortedSet");

    private static final Set<String> INJECT = Set.of(Meta.AUTOWIRED, "jakarta.inject.Inject",
            "jakarta.annotation.Resource", "javax.inject.Inject");

    private static final List<String> JPA_RELATIONS = List.of("OneToMany", "ManyToOne", "OneToOne", "ManyToMany",
            "ElementCollection", "Embedded");

    private final Connection conn;
    private final Meta meta;
    private final LocalConfig config;

    public SpringScanner(Connection conn, Meta meta, LocalConfig config) {
        this.conn = conn;
        this.meta = meta;
        this.config = config;
    }

    public void scanModule(String repo, String module, Path moduleDir, List<ClassFact> facts)
            throws SQLException, IOException {
        Set<String> autoConfigs = autoConfigurations(moduleDir);
        try (var beans = new BatchInserter(conn, "bean_definitions", "repo", "module", "bean_name", "class_fqn",
                "declared_by", "declaring_class", "factory_method", "scope", "is_primary", "conditions");
             var points = new BatchInserter(conn, "injection_points", "repo", "module", "consumer_class", "point",
                     "member", "required_type", "required_fqn", "is_collection", "qualifier", "value_expr");
             var outbound = new BatchInserter(conn, "outbound", "repo", "module", "kind", "class_fqn", "method_id",
                     "line", "http_method", "target_expr", "client_interface");
             var entities = new BatchInserter(conn, "entities", "class_fqn", "repo", "module", "table_name",
                     "schema_name");
             var relations = new BatchInserter(conn, "entity_relations", "entity_fqn", "field", "relation",
                     "target_entity", "mapped_by");
             var repositories = BatchInserter.insertOrIgnore(conn, "data_repositories", "class_fqn", "repo", "module",
                     "entity_fqn", "id_type", "base_interface")) {
            Out out = new Out(repo, module, beans, points, outbound, entities, relations, repositories);
            for (ClassFact c : facts) {
                scanClass(c, autoConfigs.contains(c.fqn), out);
            }
        }
    }

    private record Out(String repo, String module, BatchInserter beans, BatchInserter points, BatchInserter outbound,
                       BatchInserter entities, BatchInserter relations, BatchInserter repositories) {
    }

    private static Set<String> autoConfigurations(Path moduleDir) throws IOException {
        Path file = moduleDir.resolve("target/classes").resolve(AUTO_CONFIG_IMPORTS);
        Set<String> result = new HashSet<>();
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file)) {
                String s = line.strip();
                if (!s.isEmpty() && !s.startsWith("#")) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    private void scanClass(ClassFact c, boolean autoConfig, Out out) throws SQLException {
        boolean concrete = c.kind.equals("class") && !c.isAbstract;
        boolean component = concrete && meta.find(c.annotations, Meta.COMPONENT) != null;
        String classConditions = conditions(c.annotations);

        if (component || (autoConfig && concrete)) {
            AnnotationFact stereotype = meta.find(c.annotations, Meta.COMPONENT);
            String name = stereotype == null ? null : Meta.string(stereotype.attributes(), "value");
            out.beans().add(out.repo(), out.module(), name != null ? name : Meta.defaultBeanName(c.fqn), c.fqn,
                    autoConfig && !component ? "auto_config" : "scan", c.fqn, null, scope(c.annotations),
                    Meta.exact(c.annotations, SPRING_PRIMARY) != null, classConditions);
            injectionPoints(c, out);
        }
        if (component || autoConfig) {
            beanMethods(c, classConditions, out);
        }
        if (c.kind.equals("interface")) {
            dataRepository(c, out);
            httpExchangeClient(c, out);
            feignClient(c, out);
        }
        if (Meta.exact(c.annotations, "jakarta.persistence.Entity") != null
                || Meta.exact(c.annotations, "javax.persistence.Entity") != null) {
            entity(c, out);
        }
        for (MethodFact m : c.methods) {
            integrationCalls(c, m, out);
        }
    }

    private static final String SPRING_PRIMARY = Meta.SPRING + "context.annotation.Primary";

    // ── beans and injection ────────────────────────────────────────────

    private void beanMethods(ClassFact c, String classConditions, Out out) throws SQLException {
        for (MethodFact m : c.methods) {
            AnnotationFact bean = meta.find(m.annotations, Meta.BEAN);
            if (bean == null) {
                continue;
            }
            String name = Meta.string(bean.attributes(), "name", "value");
            String returnFqn = TypeNames.rawFqn(Type.getReturnType(m.descriptor));
            out.beans().add(out.repo(), out.module(), name != null ? name : m.name, returnFqn, "bean_method", c.fqn,
                    m.id, scope(m.annotations), Meta.exact(m.annotations, SPRING_PRIMARY) != null,
                    join(classConditions, conditions(m.annotations)));
            for (int i = 0; i < m.parameterTypes.size(); i++) {
                parameterPoint(c, m, i, "bean_method_param", out);
            }
        }
    }

    private void injectionPoints(ClassFact c, Out out) throws SQLException {
        List<MethodFact> constructors = c.methods.stream().filter(m -> m.name.equals("<init>")).toList();
        MethodFact chosen = null;
        for (MethodFact ctor : constructors) {
            if (injectAnnotation(ctor.annotations)) {
                chosen = ctor;
            }
        }
        if (chosen == null && constructors.size() == 1) {
            chosen = constructors.get(0);
        }
        if (chosen != null) {
            for (int i = 0; i < chosen.parameterTypes.size(); i++) {
                parameterPoint(c, chosen, i, "constructor", out);
            }
        }
        for (FieldFact f : c.fields) {
            AnnotationFact value = Meta.exact(f.annotations, Meta.VALUE);
            if (value == null && !injectAnnotation(f.annotations)) {
                continue;
            }
            Element e = element(f.typeFqn, f.typeArguments);
            out.points().add(out.repo(), out.module(), c.fqn, "field", f.name, f.type,
                    value != null ? null : e.fqn, value == null && e.collection, qualifier(f.annotations),
                    value == null ? null : Meta.string(value.attributes(), "value"));
        }
        for (MethodFact m : c.methods) {
            if (!m.name.equals("<init>") && injectAnnotation(m.annotations)) {
                for (int i = 0; i < m.parameterTypes.size(); i++) {
                    parameterPoint(c, m, i, "setter", out);
                }
            }
        }
    }

    private void parameterPoint(ClassFact c, MethodFact m, int i, String point, Out out) throws SQLException {
        List<AnnotationFact> annotations = i < m.parameterAnnotations.size() ? m.parameterAnnotations.get(i) : List.of();
        AnnotationFact value = Meta.exact(annotations, Meta.VALUE);
        Type t = Type.getArgumentTypes(m.descriptor)[i];
        if (value == null && t.getSort() != Type.OBJECT && t.getSort() != Type.ARRAY) {
            return;
        }
        List<String> typeArgs = i < m.parameterTypeArguments.size() ? m.parameterTypeArguments.get(i) : List.of();
        Element e = element(TypeNames.rawFqn(t), typeArgs);
        String name = i < m.parameterNames.size() ? m.parameterNames.get(i) : null;
        out.points().add(out.repo(), out.module(), c.fqn, point, name != null ? name : "arg" + i,
                m.parameterTypes.get(i), value != null ? null : e.fqn, value == null && e.collection,
                qualifier(annotations), value == null ? null : Meta.string(value.attributes(), "value"));
    }

    private record Element(String fqn, boolean collection) {
    }

    private static Element element(String rawFqn, List<String> typeArgs) {
        if (rawFqn != null && COLLECTIONS.contains(rawFqn) && !typeArgs.isEmpty()) {
            String arg = rawFqn.equals("java.util.Map") && typeArgs.size() == 2 ? typeArgs.get(1) : typeArgs.get(0);
            return new Element(arg, true);
        }
        return new Element(rawFqn, false);
    }

    private boolean injectAnnotation(List<AnnotationFact> annotations) {
        return annotations.stream().anyMatch(a -> INJECT.contains(a.fqn()));
    }

    private String qualifier(List<AnnotationFact> annotations) {
        for (AnnotationFact a : annotations) {
            if (a.fqn().equals(Meta.QUALIFIER)) {
                return Meta.string(a.attributes(), "value");
            }
            if (meta.implies(a.fqn(), Meta.QUALIFIER)) {
                return "@" + TypeNames.simple(a.fqn());
            }
        }
        return null;
    }

    private static String scope(List<AnnotationFact> annotations) {
        for (AnnotationFact a : annotations) {
            if (a.fqn().equals(Meta.SPRING + "context.annotation.Scope")) {
                return Meta.string(a.attributes(), "value", "scopeName");
            }
            if (a.fqn().startsWith(Meta.SPRING + "web.context.annotation.") && a.fqn().endsWith("Scope")) {
                return TypeNames.simple(a.fqn()).replace("Scope", "").toLowerCase();
            }
        }
        return null;
    }

    /** '@Profile' and '@Conditional*' guards in a compact form, e.g. 'profile=dev; property=a.b=true'. */
    static String conditions(List<AnnotationFact> annotations) {
        List<String> parts = new ArrayList<>();
        for (AnnotationFact a : annotations) {
            String simple = TypeNames.simple(a.fqn());
            Map<String, Object> at = a.attributes();
            if (simple.equals("Profile")) {
                parts.add("profile=" + String.join("|", Meta.strings(at, "value")));
            } else if (simple.equals("ConditionalOnProperty")) {
                String prefix = Meta.string(at, "prefix");
                List<String> names = Meta.strings(at, "name", "value");
                String having = Meta.string(at, "havingValue");
                for (String n : names) {
                    parts.add("property=" + (prefix == null ? "" : prefix + ".") + n + (having == null ? "" : "=" + having));
                }
            } else if (simple.startsWith("Conditional")) {
                List<String> classes = Meta.strings(at, "value", "type", "name");
                parts.add(simple + (classes.isEmpty() ? ""
                        : "(" + String.join(",", classes.stream().map(TypeNames::simple).toList()) + ")"));
            }
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private static String join(String a, String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + "; " + b;
    }

    // ── Spring Data ────────────────────────────────────────────────────

    private void dataRepository(ClassFact c, Out out) throws SQLException {
        if (Meta.exact(c.annotations, Meta.NO_REPOSITORY_BEAN) != null) {
            return;
        }
        boolean isRepository = meta.isSubtype(c.fqn, Meta.DATA_REPOSITORY) || meta.supertypes(c.fqn).stream()
                .anyMatch(s -> s.startsWith(Meta.SPRING + "data.") && s.endsWith("Repository"));
        if (!isRepository) {
            return;
        }
        String entity = null;
        String id = null;
        String base = null;
        for (var e : c.supertypeArguments.entrySet()) {
            if (e.getValue().size() == 2) {
                entity = e.getValue().get(0);
                id = e.getValue().get(1);
                base = TypeNames.simple(e.getKey());
                break;
            }
        }
        if (base == null && !c.interfaces.isEmpty()) {
            base = TypeNames.simple(c.interfaces.get(0));
        }
        out.repositories().add(c.fqn, out.repo(), out.module(), entity, id == null ? null : TypeNames.simple(id), base);
        out.beans().add(out.repo(), out.module(), Meta.defaultBeanName(c.fqn), c.fqn, "data_repository", c.fqn, null,
                null, false, conditions(c.annotations));
    }

    // ── JPA ────────────────────────────────────────────────────────────

    private void entity(ClassFact c, Out out) throws SQLException {
        AnnotationFact table = Meta.exact(c.annotations, "jakarta.persistence.Table");
        if (table == null) {
            table = Meta.exact(c.annotations, "javax.persistence.Table");
        }
        String name = table == null ? null : Meta.string(table.attributes(), "name");
        String schema = table == null ? null : Meta.string(table.attributes(), "schema");
        out.entities().add(c.fqn, out.repo(), out.module(), name != null ? name : snakeCase(TypeNames.simple(c.fqn)),
                schema);
        for (FieldFact f : c.fields) {
            for (AnnotationFact a : f.annotations) {
                String simple = TypeNames.simple(a.fqn());
                if (!(a.fqn().startsWith("jakarta.persistence.") || a.fqn().startsWith("javax.persistence."))
                        || !JPA_RELATIONS.contains(simple)) {
                    continue;
                }
                String target = Meta.string(a.attributes(), "targetEntity");
                if (target == null) {
                    target = !f.typeArguments.isEmpty() && f.typeFqn != null && COLLECTIONS.contains(f.typeFqn)
                            ? f.typeArguments.get(f.typeArguments.size() - 1) : f.typeFqn;
                }
                out.relations().add(c.fqn, f.name, snakeCase(simple), target, Meta.string(a.attributes(), "mappedBy"));
            }
        }
    }

    /**
     * Spring Boot's default physical naming (CamelCaseToUnderscoresNamingStrategy): an underscore
     * goes before an uppercase letter between two lowercase ones. OrderLine → order_line.
     */
    static String snakeCase(String name) {
        StringBuilder sb = new StringBuilder(name.replace('.', '_'));
        for (int i = 1; i < sb.length() - 1; i++) {
            if (Character.isLowerCase(sb.charAt(i - 1)) && Character.isUpperCase(sb.charAt(i))
                    && Character.isLowerCase(sb.charAt(i + 1))) {
                sb.insert(i++, '_');
            }
        }
        return sb.toString().toLowerCase();
    }

    // ── outbound: declarative HTTP clients ─────────────────────────────

    private void httpExchangeClient(ClassFact c, Out out) throws SQLException {
        AnnotationFact typeLevel = meta.find(c.annotations, Meta.HTTP_EXCHANGE);
        String base = typeLevel == null ? "" : orEmpty(Meta.string(typeLevel.attributes(), "url", "value"));
        for (MethodFact m : c.methods) {
            AnnotationFact a = meta.find(m.annotations, Meta.HTTP_EXCHANGE);
            if (a == null) {
                continue;
            }
            String method = Meta.string(a.attributes(), "method");
            if (method == null) {
                method = verbOf(TypeNames.simple(a.fqn()), "Exchange");
            }
            out.outbound().add(out.repo(), out.module(), "http_exchange", c.fqn, m.id, m.firstLine == 0 ? null : m.firstLine,
                    method, Paths.join(base, orEmpty(Meta.string(a.attributes(), "url", "value"))), c.fqn);
        }
    }

    private void feignClient(ClassFact c, Out out) throws SQLException {
        AnnotationFact feign = Meta.exact(c.annotations, Meta.FEIGN_CLIENT);
        if (feign == null) {
            return;
        }
        String base = orEmpty(Meta.string(feign.attributes(), "path"));
        AnnotationFact classMapping = meta.find(c.annotations, Meta.REQUEST_MAPPING);
        if (classMapping != null) {
            base = Paths.join(base, orEmpty(Meta.string(classMapping.attributes(), "value", "path")));
        }
        String url = Meta.string(feign.attributes(), "url");
        for (MethodFact m : c.methods) {
            AnnotationFact a = meta.find(m.annotations, Meta.REQUEST_MAPPING);
            if (a == null) {
                continue;
            }
            String method = TypeNames.simple(a.fqn()).equals("RequestMapping")
                    ? Meta.string(a.attributes(), "method") : verbOf(TypeNames.simple(a.fqn()), "Mapping");
            String path = Paths.join(base, orEmpty(Meta.string(a.attributes(), "value", "path")));
            out.outbound().add(out.repo(), out.module(), "feign", c.fqn, m.id, null, method,
                    url == null ? path : url + path, c.fqn);
        }
    }

    /** GetExchange → GET, PostMapping → POST; null for the generic annotation. */
    private static String verbOf(String simpleName, String suffix) {
        String verb = simpleName.endsWith(suffix) ? simpleName.substring(0, simpleName.length() - suffix.length()) : "";
        return switch (verb) {
            case "Get", "Post", "Put", "Delete", "Patch" -> verb.toUpperCase();
            default -> null;
        };
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // ── outbound: imperative clients and message sends ─────────────────

    private void integrationCalls(ClassFact c, MethodFact m, Out out) throws SQLException {
        String verb = null;
        for (CallFact call : m.calls) {
            String owner = call.ownerFqn();
            if (!config.keepsCall(owner, call.calleeId())) {
                continue;
            }
            String simpleOwner = TypeNames.simple(owner);
            String name = call.name();
            Integer line = call.line() == 0 ? null : call.line();
            if (simpleOwner.startsWith("Kafka") && (name.equals("send") || name.equals("sendDefault"))) {
                String target = name.equals("sendDefault") ? "${spring.kafka.template.default-topic}"
                        : stringArgument(c, m, call);
                out.outbound().add(out.repo(), out.module(), "kafka", c.fqn, m.id, line, null, target, null);
            } else if (simpleOwner.startsWith("Jms") && (name.equals("convertAndSend") || name.equals("send")
                    || name.equals("destination"))) {
                out.outbound().add(out.repo(), out.module(), "jms", c.fqn, m.id, line, null, stringArgument(c, m, call), null);
            } else if ((simpleOwner.equals("RabbitTemplate") || simpleOwner.equals("AmqpTemplate"))
                    && (name.equals("convertAndSend") || name.equals("send"))) {
                out.outbound().add(out.repo(), out.module(), "rabbit", c.fqn, m.id, line, null, stringArgument(c, m, call), null);
            } else if (simpleOwner.equals("StreamBridge") && name.equals("send")) {
                out.outbound().add(out.repo(), out.module(), "stream", c.fqn, m.id, line, null, stringArgument(c, m, call), null);
            } else if (simpleOwner.equals("ApplicationEventPublisher") && name.equals("publishEvent")) {
                String type = lastHint(call, "new:");
                out.outbound().add(out.repo(), out.module(), "event", c.fqn, m.id, line, null,
                        type == null ? null : TypeNames.simple(type), null);
            } else if (simpleOwner.equals("RestTemplate") || simpleOwner.equals("RestOperations")) {
                String method = restTemplateVerb(name, call);
                if (method != null || name.equals("exchange")) {
                    out.outbound().add(out.repo(), out.module(), "rest_template", c.fqn, m.id, line, method,
                            stringArgument(c, m, call), null);
                }
            } else if (simpleOwner.equals("RestClient") || simpleOwner.equals("WebClient")) {
                if (Set.of("get", "post", "put", "delete", "patch", "head", "options").contains(name)) {
                    verb = name.toUpperCase();
                } else if (name.equals("method")) {
                    String h = lastHint(call, "static:org.springframework.http.HttpMethod.");
                    verb = h;
                }
            } else if (simpleOwner.contains("UriSpec") && name.equals("uri") && call.descriptor().startsWith("(Ljava/lang/String;")) {
                String kind = owner.contains("reactive") ? "web_client" : "rest_client";
                out.outbound().add(out.repo(), out.module(), kind, c.fqn, m.id, line, verb, stringArgument(c, m, call), null);
                verb = null;
            }
        }
    }

    private static String restTemplateVerb(String name, CallFact call) {
        if (name.startsWith("getFor")) {
            return "GET";
        }
        if (name.startsWith("postFor")) {
            return "POST";
        }
        if (name.startsWith("patchFor")) {
            return "PATCH";
        }
        if (name.equals("put")) {
            return "PUT";
        }
        if (name.equals("delete")) {
            return "DELETE";
        }
        if (name.equals("exchange")) {
            return lastHint(call, "static:org.springframework.http.HttpMethod.");
        }
        return null;
    }

    private static String lastHint(CallFact call, String prefix) {
        List<String> hints = call.hints();
        for (int i = hints.size() - 1; i >= 0; i--) {
            if (hints.get(i).startsWith(prefix)) {
                return hints.get(i).substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * Best-effort first String argument of an API call, from the operands pushed before it: the
     * hint right after the receiver (a field of the API type), else the last string-like hint.
     */
    private String stringArgument(ClassFact c, MethodFact m, CallFact call) {
        List<String> hints = call.hints();
        int receiver = -1;
        for (int i = 0; i < hints.size(); i++) {
            String h = hints.get(i);
            if (h.startsWith("field:" + c.fqn + ".")) {
                FieldFact f = field(c, h.substring(("field:" + c.fqn + ".").length()));
                if (f != null && f.typeFqn != null && config.keepsCall(f.typeFqn, f.typeFqn + "#")) {
                    receiver = i;
                }
            }
        }
        String candidate = null;
        if (receiver >= 0 && receiver + 1 < hints.size()) {
            candidate = hints.get(receiver + 1);
        } else {
            for (int i = hints.size() - 1; i >= 0 && candidate == null; i--) {
                String h = hints.get(i);
                if (h.startsWith("str:") || h.startsWith("static:")) {
                    candidate = h;
                }
            }
        }
        return candidate == null ? null : expression(c, m, candidate);
    }

    private static String expression(ClassFact c, MethodFact m, String hint) {
        if (hint.startsWith("str:")) {
            return hint.substring(4);
        }
        if (hint.startsWith("new:")) {
            return TypeNames.simple(hint.substring(4));
        }
        if (hint.startsWith("static:")) {
            String ref = hint.substring(7);
            int dot = ref.lastIndexOf('.');
            if (ref.substring(0, dot).equals(c.fqn)) {
                FieldFact f = field(c, ref.substring(dot + 1));
                if (f != null && f.constantValue instanceof String s) {
                    return s;
                }
            }
            return TypeNames.simple(ref.substring(0, dot)) + "." + ref.substring(dot + 1);
        }
        if (hint.startsWith("field:")) {
            String ref = hint.substring(6);
            int dot = ref.lastIndexOf('.');
            String name = ref.substring(dot + 1);
            if (ref.substring(0, dot).equals(c.fqn)) {
                FieldFact f = field(c, name);
                if (f != null) {
                    AnnotationFact value = Meta.exact(f.annotations, Meta.VALUE);
                    if (value != null) {
                        return Meta.string(value.attributes(), "value");
                    }
                    if (f.constantValue instanceof String s) {
                        return s;
                    }
                }
                for (MethodFact ctor : c.methods) {
                    Integer index = ctor.name.equals("<init>") ? ctor.fieldsFromParameters.get(name) : null;
                    if (index != null && index < ctor.parameterAnnotations.size()) {
                        AnnotationFact value = Meta.exact(ctor.parameterAnnotations.get(index), Meta.VALUE);
                        if (value != null) {
                            return Meta.string(value.attributes(), "value");
                        }
                    }
                }
            }
            return "this." + name;
        }
        return null;
    }

    private static FieldFact field(ClassFact c, String name) {
        for (FieldFact f : c.fields) {
            if (f.name.equals(name)) {
                return f;
            }
        }
        return null;
    }
}
