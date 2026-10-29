package codemap.run;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs one read-only query and formats it as a compact markdown table section. */
public final class SqlRunner {

    static final int MAX_CELL = 200;
    static final int MAX_LOADED_ROWS = 5000;
    private static final int TIMEOUT_SECONDS = 30;
    private static final Pattern TABLE_REF = Pattern.compile("(?i)\\b(?:from|join)\\s+([a-z_][a-z0-9_]*)");
    private static final Pattern NO_SUCH_TABLE = Pattern.compile("no such table: ([A-Za-z0-9_.]+)");

    private final Connection conn;

    public SqlRunner(Connection conn) {
        this.conn = conn;
    }

    public Section run(String label, String sql, long offset) {
        String head = "### " + label;
        String echo = "`" + oneLine(sql, 160).replace("`", "'") + "`";
        String first = sql.stripLeading().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!Set.of("select", "with", "values", "explain", "pragma").contains(first)) {
            return error(label, head, echo, "only read queries run here (SELECT / WITH)", sql);
        }
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(TIMEOUT_SECONDS);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                List<String> units = new ArrayList<>();
                long total = 0;
                long hidden = 0;
                while (rs.next()) {
                    total++;
                    if (total <= offset) {
                        continue;
                    }
                    if (units.size() < MAX_LOADED_ROWS) {
                        StringBuilder row = new StringBuilder("|");
                        for (int c = 1; c <= cols; c++) {
                            row.append(' ').append(cell(rs.getString(c))).append(" |");
                        }
                        units.add(row.toString());
                    } else {
                        hidden++;
                    }
                }
                List<String> fixed = new ArrayList<>();
                String count = total + (total == 1 ? " row" : " rows");
                fixed.add(head + " · " + (offset > 0 ? "rows " + (offset + 1) + "+ of " + count : count) + " · " + echo);
                if (units.isEmpty()) {
                    fixed.add(offset > 0 && total > 0 ? "(no more rows)" : "(no rows)");
                } else {
                    StringBuilder header = new StringBuilder("|");
                    StringBuilder rule = new StringBuilder("|");
                    for (int c = 1; c <= cols; c++) {
                        header.append(' ').append(cell(md.getColumnLabel(c))).append(" |");
                        rule.append("---|");
                    }
                    fixed.add(header.toString());
                    fixed.add(rule.toString());
                }
                return new Section(label, fixed, units, hidden, "rows", true);
            }
        } catch (SQLException e) {
            return error(label, head, echo, cleanMessage(e), sql);
        }
    }

    private Section error(String label, String head, String echo, String message, String sql) {
        List<String> lines = new ArrayList<>();
        lines.add(head + " · ERROR · " + echo);
        lines.add("ERROR: " + message);
        lines.addAll(hints(message, sql));
        return Section.text(label, lines);
    }

    /** Column lists of the tables the query mentions, or close table names when one is unknown. */
    private List<String> hints(String message, String sql) {
        List<String> hints = new ArrayList<>();
        try {
            Set<String> objects = objects();
            Matcher missing = NO_SUCH_TABLE.matcher(message);
            if (missing.find()) {
                String name = missing.group(1).toLowerCase(Locale.ROOT);
                List<String> similar = objects.stream()
                        .filter(o -> o.contains(name) || name.contains(o) || o.startsWith(name.substring(0, Math.min(3, name.length()))))
                        .toList();
                hints.add("hint: tables/views are " + String.join(", ", similar.isEmpty() ? objects : similar));
            }
            Set<String> referenced = new LinkedHashSet<>();
            Matcher m = TABLE_REF.matcher(sql);
            while (m.find()) {
                String t = m.group(1).toLowerCase(Locale.ROOT);
                if (objects.contains(t)) {
                    referenced.add(t);
                }
            }
            for (String t : referenced) {
                hints.add("hint: columns of " + t + ": " + String.join(", ", columns(t)));
            }
        } catch (SQLException ignored) {
            // hints are optional
        }
        return hints;
    }

    private Set<String> objects() throws SQLException {
        Set<String> names = new java.util.TreeSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type IN ('table', 'view')"
                     + " AND name NOT LIKE 'sqlite_%' AND name <> 'schema_doc'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private List<String> columns(String object) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM pragma_table_info('" + object.replace("'", "") + "')")) {
            while (rs.next()) {
                cols.add(rs.getString(1));
            }
        }
        return cols;
    }

    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace("\r", "").replace("\n", " ⏎ ").replace("|", "\\|");
        return v.length() > MAX_CELL ? v.substring(0, MAX_CELL - 1) + "…" : v;
    }

    static String oneLine(String s, int max) {
        String v = s.strip().replaceAll("\\s+", " ");
        return v.length() > max ? v.substring(0, max - 1) + "…" : v;
    }

    private static String cleanMessage(SQLException e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        // sqlite-jdbc: "[SQLITE_ERROR] SQL error or missing database (no such column: x)"
        Matcher m = Pattern.compile("\\((.*)\\)\\s*$", Pattern.DOTALL).matcher(msg);
        return m.find() ? m.group(1) : msg;
    }
}
