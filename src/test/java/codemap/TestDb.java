package codemap;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Query helpers for assertions: rows rendered as "a|b|c" strings. */
public final class TestDb {

    private TestDb() {
    }

    public static List<String> rows(Connection conn, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    if (i > 1) {
                        sb.append('|');
                    }
                    sb.append(rs.getString(i));
                }
                rows.add(sb.toString());
            }
        }
        return rows;
    }

    public static String one(Connection conn, String sql) throws SQLException {
        List<String> rows = rows(conn, sql);
        if (rows.size() != 1) {
            throw new AssertionError("expected 1 row for " + sql + " but got " + rows);
        }
        return rows.get(0);
    }
}
