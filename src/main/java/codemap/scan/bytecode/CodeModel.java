package codemap.scan.bytecode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import codemap.db.BatchInserter;

/**
 * Workspace-wide derivations, recomputed after every scan from the per-class rows:
 * external type rows, transitive type hierarchy, transitive meta-annotations.
 */
public final class CodeModel {

    /**
     * Meta-annotations of well-known Spring annotations, used when their jars are not on the
     * indexed classpath (no codemap.classpath file). Real class files win when available.
     */
    static final Map<String, List<String>> KNOWN_META = Map.ofEntries(
            Map.entry("org.springframework.stereotype.Service", List.of("org.springframework.stereotype.Component")),
            Map.entry("org.springframework.stereotype.Repository", List.of("org.springframework.stereotype.Component")),
            Map.entry("org.springframework.stereotype.Controller", List.of("org.springframework.stereotype.Component")),
            Map.entry("org.springframework.web.bind.annotation.RestController",
                    List.of("org.springframework.stereotype.Controller", "org.springframework.web.bind.annotation.ResponseBody")),
            Map.entry("org.springframework.web.bind.annotation.ControllerAdvice", List.of("org.springframework.stereotype.Component")),
            Map.entry("org.springframework.web.bind.annotation.RestControllerAdvice",
                    List.of("org.springframework.web.bind.annotation.ControllerAdvice")),
            Map.entry("org.springframework.context.annotation.Configuration", List.of("org.springframework.stereotype.Component")),
            Map.entry("org.springframework.boot.SpringBootConfiguration",
                    List.of("org.springframework.context.annotation.Configuration")),
            Map.entry("org.springframework.boot.autoconfigure.SpringBootApplication",
                    List.of("org.springframework.boot.SpringBootConfiguration",
                            "org.springframework.boot.autoconfigure.EnableAutoConfiguration",
                            "org.springframework.context.annotation.ComponentScan")),
            Map.entry("org.springframework.boot.autoconfigure.AutoConfiguration",
                    List.of("org.springframework.context.annotation.Configuration")),
            Map.entry("org.springframework.boot.context.properties.ConfigurationProperties", List.of()),
            Map.entry("org.springframework.web.bind.annotation.GetMapping",
                    List.of("org.springframework.web.bind.annotation.RequestMapping")),
            Map.entry("org.springframework.web.bind.annotation.PostMapping",
                    List.of("org.springframework.web.bind.annotation.RequestMapping")),
            Map.entry("org.springframework.web.bind.annotation.PutMapping",
                    List.of("org.springframework.web.bind.annotation.RequestMapping")),
            Map.entry("org.springframework.web.bind.annotation.DeleteMapping",
                    List.of("org.springframework.web.bind.annotation.RequestMapping")),
            Map.entry("org.springframework.web.bind.annotation.PatchMapping",
                    List.of("org.springframework.web.bind.annotation.RequestMapping")),
            Map.entry("org.springframework.web.service.annotation.GetExchange",
                    List.of("org.springframework.web.service.annotation.HttpExchange")),
            Map.entry("org.springframework.web.service.annotation.PostExchange",
                    List.of("org.springframework.web.service.annotation.HttpExchange")),
            Map.entry("org.springframework.web.service.annotation.PutExchange",
                    List.of("org.springframework.web.service.annotation.HttpExchange")),
            Map.entry("org.springframework.web.service.annotation.DeleteExchange",
                    List.of("org.springframework.web.service.annotation.HttpExchange")),
            Map.entry("org.springframework.web.service.annotation.PatchExchange",
                    List.of("org.springframework.web.service.annotation.HttpExchange")),
            Map.entry("org.springframework.transaction.event.TransactionalEventListener",
                    List.of("org.springframework.context.event.EventListener")));

    private CodeModel() {
    }

    public static void rebuild(Connection conn, ClasspathIndex classpath) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM type_hierarchy WHERE depth > 1");
            st.executeUpdate("DELETE FROM type_hierarchy WHERE class_fqn IN (SELECT fqn FROM classes WHERE is_external = 1)");
            st.executeUpdate("DELETE FROM annotations WHERE class_fqn IN (SELECT fqn FROM classes WHERE is_external = 1)");
            st.executeUpdate("DELETE FROM classes WHERE is_external = 1");
            st.executeUpdate("DELETE FROM annotation_meta");
            addExternalTypes(conn, classpath);
            closeHierarchy(st);
            closeMetaAnnotations(conn, st);
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    /**
     * Adds a thin row for every referenced supertype and annotation type that is not scanned
     * code, following their own supertypes / meta-annotations through the classpath.
     */
    private static void addExternalTypes(Connection conn, ClasspathIndex classpath) throws SQLException {
        Set<String> internal = strings(conn, "SELECT fqn FROM classes WHERE is_external = 0");
        Deque<String> queue = new ArrayDeque<>();
        queue.addAll(strings(conn, "SELECT DISTINCT supertype_fqn FROM type_hierarchy"));
        queue.addAll(strings(conn, "SELECT DISTINCT annotation_fqn FROM annotations"));
        Set<String> done = new HashSet<>(internal);

        try (var classes = BatchInserter.insertOrIgnore(conn, "classes", "fqn", "simple_name", "package", "kind",
                "visibility", "is_abstract", "superclass", "is_external");
             var hierarchy = BatchInserter.insertOrIgnore(conn, "type_hierarchy", "class_fqn", "supertype_fqn",
                     "relation", "depth");
             var annotations = new BatchInserter(conn, "annotations", "target_kind", "target", "class_fqn",
                     "annotation_fqn", "annotation", "attributes")) {
            while (!queue.isEmpty()) {
                String fqn = queue.poll();
                if (!done.add(fqn)) {
                    continue;
                }
                String pkg = TypeNames.packageOf(fqn);
                String simple = fqn.substring(pkg.isEmpty() ? 0 : pkg.length() + 1);
                Optional<ClassFact> header = fqn.startsWith("java.") ? Optional.empty() : classpath.header(fqn);
                if (header.isPresent()) {
                    ClassFact h = header.get();
                    classes.add(fqn, simple, pkg, h.kind, h.visibility, h.isAbstract, h.superclass, 1);
                    if (h.superclass != null) {
                        hierarchy.add(fqn, h.superclass, "extends", 1);
                        queue.add(h.superclass);
                    }
                    for (String i : h.interfaces) {
                        hierarchy.add(fqn, i, "implements", 1);
                        queue.add(i);
                    }
                    if ("annotation".equals(h.kind)) {
                        for (ClassFact.AnnotationFact a : h.annotations) {
                            annotations.add("class", fqn, fqn, a.fqn(), TypeNames.simple(a.fqn()),
                                    a.attributes().isEmpty() ? null : Json.write(a.attributes()));
                            queue.add(a.fqn());
                        }
                    }
                } else {
                    List<String> metas = KNOWN_META.get(fqn);
                    classes.add(fqn, simple, pkg, metas != null ? "annotation" : null, null, null, null, 1);
                    if (metas != null) {
                        for (String meta : metas) {
                            annotations.add("class", fqn, fqn, meta, TypeNames.simple(meta), null);
                            queue.add(meta);
                        }
                    }
                }
            }
        }
    }

    private static void closeHierarchy(Statement st) throws SQLException {
        st.executeUpdate("DROP TABLE IF EXISTS temp.closure");
        st.executeUpdate("""
                CREATE TEMP TABLE closure AS
                WITH RECURSIVE h(c, s, rel, depth) AS (
                  SELECT class_fqn, supertype_fqn, relation, 1 FROM type_hierarchy WHERE depth = 1
                  UNION
                  SELECT h.c, d.supertype_fqn,
                         CASE WHEN h.rel = 'extends' AND d.relation = 'extends' THEN 'extends' ELSE 'implements' END,
                         h.depth + 1
                  FROM h JOIN type_hierarchy d ON d.class_fqn = h.s AND d.depth = 1
                  WHERE h.depth < 30
                )
                SELECT c, s, MIN(rel) AS rel, MIN(depth) AS depth FROM h
                WHERE depth > 1 AND c IN (SELECT fqn FROM classes WHERE is_external = 0)
                GROUP BY c, s
                """);
        st.executeUpdate("INSERT OR IGNORE INTO type_hierarchy SELECT c, s, rel, depth FROM temp.closure");
        st.executeUpdate("DROP TABLE temp.closure");
    }

    private static void closeMetaAnnotations(Connection conn, Statement st) throws SQLException {
        st.executeUpdate("DROP TABLE IF EXISTS temp.meta_direct");
        st.executeUpdate("""
                CREATE TEMP TABLE meta_direct AS
                SELECT DISTINCT a.target AS a, a.annotation_fqn AS meta
                FROM annotations a JOIN classes c ON c.fqn = a.target AND c.kind = 'annotation'
                WHERE a.target_kind = 'class'
                  AND a.annotation_fqn NOT LIKE 'java.lang.annotation.%'
                  AND a.annotation_fqn NOT LIKE 'kotlin.%'
                  AND a.annotation_fqn <> a.target
                """);
        st.executeUpdate("""
                INSERT INTO annotation_meta
                WITH RECURSIVE m(a, meta, depth) AS (
                  SELECT a, meta, 1 FROM meta_direct
                  UNION
                  SELECT m.a, d.meta, m.depth + 1 FROM m JOIN meta_direct d ON d.a = m.meta WHERE m.depth < 10
                )
                SELECT a, meta, MIN(depth) FROM m WHERE a <> meta GROUP BY a, meta
                """);
        st.executeUpdate("DROP TABLE temp.meta_direct");
    }

    static Set<String> strings(Connection conn, String sql) throws SQLException {
        Set<String> out = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (rs.getString(1) != null) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }
}
