package codemap.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Human/LLM-oriented documentation of every table, view and column, read from schema-doc.yml.
 * A view may declare {@code columns: {same_as: table}} to reuse a table's column docs.
 */
public record SchemaDoc(List<ObjectDoc> objects) {

    public record ObjectDoc(String name, String kind, String description, String exampleQuery,
                            Map<String, String> columns) {
    }

    public static SchemaDoc load() {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> root = yaml.load(Database.readResource("/codemap/schema-doc.yml"));
        List<ObjectDoc> objects = new ArrayList<>();
        Map<String, Map<String, String>> tableColumns = new LinkedHashMap<>();
        for (String kind : List.of("tables", "views")) {
            Map<String, Map<String, Object>> section = cast(root.get(kind));
            for (var entry : section.entrySet()) {
                Map<String, Object> body = entry.getValue();
                Map<String, Object> rawColumns = cast(body.get("columns"));
                Map<String, String> columns = new LinkedHashMap<>();
                if (rawColumns.containsKey("same_as")) {
                    String base = String.valueOf(rawColumns.get("same_as"));
                    Map<String, String> baseColumns = tableColumns.get(base);
                    if (baseColumns == null) {
                        throw new IllegalStateException("schema-doc: " + entry.getKey()
                                + " refers to unknown table " + base);
                    }
                    columns.putAll(baseColumns);
                } else {
                    rawColumns.forEach((k, v) -> columns.put(k, String.valueOf(v)));
                }
                String singular = kind.equals("tables") ? "table" : "view";
                if (singular.equals("table")) {
                    tableColumns.put(entry.getKey(), columns);
                }
                objects.add(new ObjectDoc(entry.getKey(), singular, String.valueOf(body.get("doc")),
                        (String) body.get("example"), columns));
            }
        }
        return new SchemaDoc(List.copyOf(objects));
    }

    public ObjectDoc object(String name) {
        return objects.stream().filter(o -> o.name().equals(name)).findFirst().orElse(null);
    }

    void insertInto(Connection conn) throws SQLException {
        String sql = "INSERT INTO schema_doc(object, object_kind, column, description, example_query)"
                + " VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ObjectDoc o : objects) {
                add(ps, o.name(), o.kind(), "", o.description(), o.exampleQuery());
                for (var c : o.columns().entrySet()) {
                    add(ps, o.name(), o.kind(), c.getKey(), c.getValue(), null);
                }
            }
            ps.executeBatch();
        }
    }

    private static void add(PreparedStatement ps, String object, String kind, String column,
                            String description, String example) throws SQLException {
        ps.setString(1, object);
        ps.setString(2, kind);
        ps.setString(3, column);
        ps.setString(4, description);
        ps.setString(5, example);
        ps.addBatch();
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) {
        return (T) (o == null ? Map.of() : o);
    }
}
