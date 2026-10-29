package codemap.scan.bytecode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import codemap.scan.LocalConfig;

/**
 * Assigns classes.role, first matching rule wins: local rules, then built-in Spring rules from
 * most to least specific. Annotation rules see through meta-annotations.
 */
public final class Roles {

    private static final String SPRING = "org.springframework.";

    private record AnnotationRule(String role, List<String> annotations, boolean onMethods) {
    }

    private static final List<AnnotationRule> BUILT_IN_BEFORE_REPOSITORY = List.of(
            new AnnotationRule("application", List.of(SPRING + "boot.autoconfigure.SpringBootApplication"), false),
            new AnnotationRule("controller", List.of(SPRING + "stereotype.Controller",
                    SPRING + "web.bind.annotation.ControllerAdvice"), false),
            new AnnotationRule("configuration", List.of(SPRING + "context.annotation.Configuration"), false),
            new AnnotationRule("entity", List.of("jakarta.persistence.Entity", "jakarta.persistence.Embeddable",
                    "jakarta.persistence.MappedSuperclass", "javax.persistence.Entity",
                    "javax.persistence.Embeddable", "javax.persistence.MappedSuperclass"), false));

    private static final List<String> CLIENT_ANNOTATIONS = List.of(
            SPRING + "web.service.annotation.HttpExchange", SPRING + "cloud.openfeign.FeignClient");

    private static final List<String> LISTENER_ANNOTATIONS = List.of(
            SPRING + "kafka.annotation.KafkaListener", SPRING + "kafka.annotation.KafkaHandler",
            SPRING + "jms.annotation.JmsListener", SPRING + "amqp.rabbit.annotation.RabbitListener",
            SPRING + "context.event.EventListener", SPRING + "scheduling.annotation.Scheduled",
            SPRING + "scheduling.annotation.Schedules");

    private Roles() {
    }

    public static void assign(Connection conn, LocalConfig config) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE classes SET role = NULL, role_reason = NULL");
            createEffectiveAnnotations(st);

            for (LocalConfig.RoleRule rule : config.roles()) {
                if (rule.annotation() != null) {
                    byAnnotation(conn, rule.role(), List.of(rule.annotation()), false, rule.describe());
                } else if (rule.supertype() != null) {
                    bySupertype(conn, rule.role(), rule.supertype(), rule.describe());
                } else {
                    byPackage(conn, rule);
                }
            }
            for (AnnotationRule r : BUILT_IN_BEFORE_REPOSITORY) {
                byAnnotation(conn, r.role(), r.annotations(), r.onMethods(), null);
            }
            bySupertype(conn, "repository", SPRING + "data.repository.Repository", null);
            // Without the Spring Data jars on the classpath, any Spring Data supertype still counts.
            update(conn, "repository", "extends Spring Data repository",
                    "kind = 'interface' AND fqn IN (SELECT class_fqn FROM type_hierarchy"
                            + " WHERE supertype_fqn LIKE 'org.springframework.data.%Repository')");
            byAnnotation(conn, "repository", List.of(SPRING + "stereotype.Repository"), false, null);
            byAnnotation(conn, "client", CLIENT_ANNOTATIONS, false, null);
            byAnnotation(conn, "client", CLIENT_ANNOTATIONS, true, null);
            byAnnotation(conn, "listener", LISTENER_ANNOTATIONS, true, null);
            byAnnotation(conn, "listener", List.of(SPRING + "kafka.annotation.KafkaListener",
                    SPRING + "amqp.rabbit.annotation.RabbitListener"), false, null);
            byAnnotation(conn, "service", List.of(SPRING + "stereotype.Service"), false, null);
            byAnnotation(conn, "component", List.of(SPRING + "stereotype.Component"), false, null);

            // Annotation types (e.g. a custom stereotype) are not components themselves.
            st.executeUpdate("UPDATE classes SET role = NULL, role_reason = NULL WHERE kind = 'annotation'");
            st.executeUpdate("DROP TABLE temp.eff_class");
            st.executeUpdate("DROP TABLE temp.eff_method");
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    /** eff_*(fqn, via, via_fqn, ann): the annotation as written (via) and every annotation it implies (ann). */
    private static void createEffectiveAnnotations(Statement st) throws SQLException {
        st.executeUpdate("DROP TABLE IF EXISTS temp.eff_class");
        st.executeUpdate("DROP TABLE IF EXISTS temp.eff_method");
        for (String[] t : new String[][]{{"eff_class", "a.target", "'class'"}, {"eff_method", "a.class_fqn", "'method'"}}) {
            st.executeUpdate("CREATE TEMP TABLE " + t[0] + " AS"
                    + " SELECT " + t[1] + " AS fqn, a.annotation AS via, a.annotation_fqn AS via_fqn,"
                    + " a.annotation_fqn AS ann"
                    + " FROM annotations a JOIN classes c ON c.fqn = " + t[1] + " AND c.is_external = 0"
                    + " WHERE a.target_kind = " + t[2]
                    + " UNION SELECT " + t[1] + ", a.annotation, a.annotation_fqn, m.meta_annotation_fqn"
                    + " FROM annotations a JOIN classes c ON c.fqn = " + t[1] + " AND c.is_external = 0"
                    + " JOIN annotation_meta m ON m.annotation_fqn = a.annotation_fqn"
                    + " WHERE a.target_kind = " + t[2]);
        }
    }

    private static void byAnnotation(Connection conn, String role, List<String> annotations, boolean onMethods,
                                     String reason) throws SQLException {
        String table = onMethods ? "temp.eff_method" : "temp.eff_class";
        // Reason: the annotation as written ('@RestController'); for custom annotations also what
        // it implies ('@Service via @UseCase').
        String sql = "UPDATE classes SET role = ?, role_reason = COALESCE(?, (SELECT"
                + " CASE WHEN e.via_fqn = ? OR e.via_fqn LIKE 'org.springframework.%' THEN '@' || e.via"
                + " ELSE '@' || ? || ' via @' || e.via END" + (onMethods ? " || ' on a method'" : "")
                + " FROM " + table + " e WHERE e.fqn = classes.fqn AND e.ann = ?"
                + " ORDER BY e.via_fqn = ? DESC, e.via LIMIT 1))"
                + " WHERE role IS NULL AND is_external = 0 AND fqn IN (SELECT fqn FROM " + table + " WHERE ann = ?)";
        for (String annotation : annotations) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, List.of(role, reason == null ? NULL : reason, annotation, TypeNames.simple(annotation),
                        annotation, annotation, annotation));
                ps.executeUpdate();
            }
        }
    }

    private static void bySupertype(Connection conn, String role, String supertype, String reason) throws SQLException {
        update(conn, role, reason != null ? reason : "extends " + TypeNames.simple(supertype),
                "fqn IN (SELECT class_fqn FROM type_hierarchy WHERE supertype_fqn = ?)", supertype);
    }

    private static void byPackage(Connection conn, LocalConfig.RoleRule rule) throws SQLException {
        var pattern = rule.packagePattern();
        List<String> matches = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT fqn FROM classes WHERE role IS NULL AND is_external = 0"
                     + " AND kind <> 'annotation'")) {
            while (rs.next()) {
                if (pattern.matcher(rs.getString(1)).matches()) {
                    matches.add(rs.getString(1));
                }
            }
        }
        for (String fqn : matches) {
            update(conn, rule.role(), rule.describe(), "fqn = ?", fqn);
        }
    }

    private static void update(Connection conn, String role, String reason, String where, Object... args)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE classes SET role = ?, role_reason = ?"
                + " WHERE role IS NULL AND is_external = 0 AND " + where)) {
            List<Object> all = new ArrayList<>(List.of(role, reason));
            all.addAll(List.of(args));
            bind(ps, all);
            ps.executeUpdate();
        }
    }

    private static final Object NULL = new Object();

    private static void bind(PreparedStatement ps, List<?> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            Object v = args.get(i);
            if (v == NULL || v == null) {
                ps.setNull(i + 1, java.sql.Types.VARCHAR);
            } else {
                ps.setObject(i + 1, v);
            }
        }
    }
}
