package codemap.report;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Small query helper for report generators. */
final class Db {

    private final Connection conn;

    Db(Connection conn) {
        this.conn = conn;
    }

    List<String[]> rows(String sql, Object... args) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    String[] row = new String[n];
                    for (int i = 0; i < n; i++) {
                        row[i] = rs.getString(i + 1);
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    String one(String sql, Object... args) throws SQLException {
        List<String[]> r = rows(sql, args);
        return r.isEmpty() ? null : r.get(0)[0];
    }

    int count(String sql, Object... args) throws SQLException {
        String v = one(sql, args);
        return v == null ? 0 : Integer.parseInt(v);
    }

    /** {@code com.example.Foo#bar(String)} → {@code Foo#bar}; class fqn → simple name. */
    static String shortMethod(String methodId) {
        if (methodId == null) {
            return "";
        }
        int hash = methodId.indexOf('#');
        String cls = hash < 0 ? methodId : methodId.substring(0, hash);
        String simple = cls.substring(cls.lastIndexOf('.') + 1).replace('$', '.');
        if (hash < 0) {
            return simple;
        }
        String m = methodId.substring(hash + 1);
        int paren = m.indexOf('(');
        return simple + "#" + (paren < 0 ? m : m.substring(0, paren));
    }

    static String simple(String fqn) {
        return fqn == null ? "" : shortMethod(fqn);
    }
}
