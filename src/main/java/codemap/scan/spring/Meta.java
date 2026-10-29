package codemap.scan.spring;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import codemap.scan.bytecode.ClassFact.AnnotationFact;

/** Annotation and type knowledge loaded from the database after the code-model pass. */
public final class Meta {

    public static final String SPRING = "org.springframework.";
    public static final String COMPONENT = SPRING + "stereotype.Component";
    public static final String QUALIFIER = SPRING + "beans.factory.annotation.Qualifier";
    public static final String VALUE = SPRING + "beans.factory.annotation.Value";
    public static final String AUTOWIRED = SPRING + "beans.factory.annotation.Autowired";
    public static final String BEAN = SPRING + "context.annotation.Bean";
    public static final String REQUEST_MAPPING = SPRING + "web.bind.annotation.RequestMapping";
    public static final String HTTP_EXCHANGE = SPRING + "web.service.annotation.HttpExchange";
    public static final String FEIGN_CLIENT = SPRING + "cloud.openfeign.FeignClient";
    public static final String DATA_REPOSITORY = SPRING + "data.repository.Repository";
    public static final String NO_REPOSITORY_BEAN = SPRING + "data.repository.NoRepositoryBean";

    private final Map<String, Set<String>> metaOf;
    private final Map<String, Set<String>> supertypes;

    private Meta(Map<String, Set<String>> metaOf, Map<String, Set<String>> supertypes) {
        this.metaOf = metaOf;
        this.supertypes = supertypes;
    }

    public static Meta load(Connection conn) throws SQLException {
        return new Meta(pairs(conn, "SELECT annotation_fqn, meta_annotation_fqn FROM annotation_meta"),
                pairs(conn, "SELECT class_fqn, supertype_fqn FROM type_hierarchy"));
    }

    private static Map<String, Set<String>> pairs(Connection conn, String sql) throws SQLException {
        Map<String, Set<String>> map = new HashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                map.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
            }
        }
        return map;
    }

    /** True if the annotation type is {@code target} or carries it as a (transitive) meta-annotation. */
    public boolean implies(String annotationFqn, String target) {
        return annotationFqn.equals(target) || metaOf.getOrDefault(annotationFqn, Set.of()).contains(target);
    }

    /** First annotation in the list that is or implies {@code target}. */
    public AnnotationFact find(List<AnnotationFact> annotations, String target) {
        for (AnnotationFact a : annotations) {
            if (implies(a.fqn(), target)) {
                return a;
            }
        }
        return null;
    }

    /** The annotation exactly of this type (not via meta-annotations). */
    public static AnnotationFact exact(List<AnnotationFact> annotations, String fqn) {
        for (AnnotationFact a : annotations) {
            if (a.fqn().equals(fqn)) {
                return a;
            }
        }
        return null;
    }

    public boolean isSubtype(String classFqn, String supertypeFqn) {
        return classFqn.equals(supertypeFqn) || supertypes.getOrDefault(classFqn, Set.of()).contains(supertypeFqn);
    }

    public Set<String> supertypes(String classFqn) {
        return supertypes.getOrDefault(classFqn, Set.of());
    }

    // ── attribute helpers ──────────────────────────────────────────────

    /** String values of an attribute that may be a single value or an array. */
    public static List<String> strings(Map<String, Object> attributes, String... names) {
        for (String name : names) {
            Object v = attributes.get(name);
            if (v instanceof List<?> list && !list.isEmpty()) {
                return list.stream().map(String::valueOf).toList();
            }
            if (v != null && !(v instanceof List<?>) && !String.valueOf(v).isEmpty()) {
                return List.of(String.valueOf(v));
            }
        }
        return List.of();
    }

    public static String string(Map<String, Object> attributes, String... names) {
        List<String> values = strings(attributes, names);
        return values.isEmpty() ? null : values.get(0);
    }

    /** Spring's default bean name: decapitalized simple name (kept if it starts with two capitals). */
    public static String defaultBeanName(String classFqn) {
        String simple = classFqn.substring(classFqn.lastIndexOf('.') + 1);
        simple = simple.substring(simple.lastIndexOf('$') + 1);
        if (simple.length() > 1 && Character.isUpperCase(simple.charAt(0)) && Character.isUpperCase(simple.charAt(1))) {
            return simple;
        }
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }
}
