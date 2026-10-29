package codemap.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    @TempDir
    Path tmp;

    @Test
    void createsSchemaAndEveryViewIsQueryable() throws Exception {
        try (Connection conn = Database.openForWrite(tmp.resolve("x.db"))) {
            Map<String, String> objects = objects(conn);
            assertTrue(objects.size() > 30, "tables + views: " + objects.keySet());
            try (Statement st = conn.createStatement()) {
                for (String name : objects.keySet()) {
                    st.executeQuery("SELECT * FROM " + name + " LIMIT 0").close();
                }
            }
        }
    }

    @Test
    void schemaDocCoversEveryObjectAndColumn() throws Exception {
        SchemaDoc doc = SchemaDoc.load();
        try (Connection conn = Database.openForWrite(tmp.resolve("x.db"))) {
            Map<String, String> objects = objects(conn);
            List<String> problems = new ArrayList<>();
            for (var e : objects.entrySet()) {
                SchemaDoc.ObjectDoc o = doc.object(e.getKey());
                if (o == null) {
                    problems.add("undocumented " + e.getValue() + " " + e.getKey());
                    continue;
                }
                if (!o.kind().equals(e.getValue())) {
                    problems.add(e.getKey() + " documented as " + o.kind());
                }
                Set<String> actual = columns(conn, e.getKey());
                if (!actual.equals(o.columns().keySet())) {
                    problems.add(e.getKey() + ": columns " + actual + " vs documented " + o.columns().keySet());
                }
                if (o.exampleQuery() == null || o.exampleQuery().isBlank()) {
                    problems.add(e.getKey() + ": no example query");
                }
            }
            for (SchemaDoc.ObjectDoc o : doc.objects()) {
                if (!objects.containsKey(o.name())) {
                    problems.add("documented but missing: " + o.name());
                }
            }
            assertTrue(problems.isEmpty(), String.join("\n", problems));
        }
    }

    @Test
    void everyExampleQueryRuns() throws Exception {
        try (Connection conn = Database.openForWrite(tmp.resolve("x.db"));
             Statement st = conn.createStatement()) {
            for (SchemaDoc.ObjectDoc o : SchemaDoc.load().objects()) {
                try {
                    st.executeQuery(o.exampleQuery()).close();
                } catch (SQLException e) {
                    throw new AssertionError("example for " + o.name() + " fails: " + e.getMessage(), e);
                }
            }
        }
    }

    @Test
    void schemaDocTableIsFilled() throws Exception {
        try (Connection conn = Database.openForWrite(tmp.resolve("x.db"));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM schema_doc WHERE column = ''")) {
            assertTrue(rs.next());
            assertEquals(SchemaDoc.load().objects().size(), rs.getInt(1));
        }
    }

    @Test
    void reopeningKeepsSchemaAndReadOnlyRejectsWrites() throws Exception {
        Path db = tmp.resolve("x.db");
        Database.openForWrite(db).close();
        Database.openForWrite(db).close();
        try (Connection ro = Database.openReadOnly(db); Statement st = ro.createStatement()) {
            assertNotNull(st.executeQuery("SELECT COUNT(*) FROM repos"));
            assertThrows(SQLException.class, () -> st.execute("DELETE FROM schema_doc"));
            assertThrows(SQLException.class, () -> st.execute("CREATE TABLE evil(x)"));
        }
    }

    @Test
    void readOnlyRequiresExistingDb() {
        assertThrows(SQLException.class, () -> Database.openReadOnly(tmp.resolve("missing.db")));
    }

    @Test
    void sourceColumnRejectsUnknownValues() throws Exception {
        try (Connection conn = Database.openForWrite(tmp.resolve("x.db"));
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO beans(service, bean_name) VALUES ('s', 'b')");
            assertThrows(SQLException.class,
                    () -> st.execute("INSERT INTO beans(service, bean_name, source) VALUES ('s', 'b', 'guess')"));
            try (ResultSet rs = st.executeQuery("SELECT source FROM beans")) {
                assertTrue(rs.next());
                assertEquals("static", rs.getString(1));
                assertFalse(rs.next());
            }
        }
    }

    private static Map<String, String> objects(Connection conn) throws SQLException {
        Map<String, String> result = new TreeMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name, type FROM sqlite_master WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%'")) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getString(2));
            }
        }
        return result;
    }

    private static Set<String> columns(Connection conn, String object) throws SQLException {
        Set<String> result = new LinkedHashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM pragma_table_info('" + object + "')")) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }
}
