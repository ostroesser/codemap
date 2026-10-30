package codemap.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the requests from an LLM answer, in order of appearance.
 * <ul>
 *   <li>Fenced {@code ```sql} blocks are split into statements; {@code ```cmd} blocks give one
 *       command per line.</li>
 *   <li>Text without fences (e.g. the content of a code block copied with the chat's copy
 *       button) is read as SQL. Comments are allowed anywhere; fragments that contain no query
 *       (prose) are skipped and counted.</li>
 *   <li>In SQL, a line {@code -- cmd: source Foo#bar}, or a bare {@code source ...} /
 *       {@code diagram ...} line between statements, is a command. This lets one copied block
 *       carry queries and commands together.</li>
 * </ul>
 */
public final class Blocks {

    public enum Kind { SQL, CMD }

    public record Request(Kind kind, String text) {
    }

    /** @param ignored text fragments without a query that were skipped (unfenced input only) */
    public record Extraction(List<Request> requests, int ignored) {
    }

    private static final Pattern CMD_COMMENT = Pattern.compile("^\\s*--\\s*cmd:\\s*(\\S.*?)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_CMD = Pattern.compile("^\\s*((?:source|diagram)\\s+[^;\\s][^;]*?)\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_START = Pattern.compile("(?im)^\\s*(select|with|values|explain|pragma)\\b");
    private static final List<String> QUERY_KEYWORDS = List.of("select", "with", "values", "explain", "pragma");
    /** Statements that are SQL but not reads: kept so the runner refuses them visibly. */
    private static final List<String> WRITE_KEYWORDS = List.of("insert", "update", "delete", "replace", "create",
            "drop", "alter", "attach", "detach", "vacuum", "reindex");

    private Blocks() {
    }

    public static List<Request> extract(String input) {
        return extractAll(input).requests();
    }

    public static Extraction extractAll(String input) {
        String text = input.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\n", -1);
        boolean fenced = false;
        for (String line : lines) {
            if (line.strip().startsWith("```") || line.strip().startsWith("~~~")) {
                fenced = true;
                break;
            }
        }
        List<Request> requests = new ArrayList<>();
        if (!fenced) {
            int ignored = sql(text, true, requests);
            return new Extraction(requests, ignored);
        }
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            String fence = line.startsWith("```") ? "```" : line.startsWith("~~~") ? "~~~" : null;
            if (fence == null) {
                continue;
            }
            String lang = line.substring(3).strip().toLowerCase(Locale.ROOT);
            StringBuilder body = new StringBuilder();
            int j = i + 1;
            while (j < lines.length && !lines[j].strip().startsWith(fence)) {
                body.append(lines[j]).append('\n');
                j++;
            }
            if (lang.equals("sql") || lang.equals("sqlite")) {
                sql(body.toString(), false, requests);
            } else if (lang.equals("cmd") || lang.equals("codemap")) {
                for (String cmd : body.toString().split("\n")) {
                    String c = cmd.strip();
                    if (!c.isEmpty() && !c.startsWith("#")) {
                        requests.add(new Request(Kind.CMD, c));
                    }
                }
            }
            i = j;
        }
        return new Extraction(requests, 0);
    }

    /**
     * SQL text with embedded command lines. Statements are cut at command lines, then on ';'.
     * With {@code skipProse}, fragments without a query are dropped (and counted), and text
     * before the first query line of a fragment is removed.
     */
    private static int sql(String text, boolean skipProse, List<Request> out) {
        int ignored = 0;
        StringBuilder buffer = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String cmd = command(line, atStatementBoundary(buffer));
            if (cmd != null) {
                ignored += flush(buffer, skipProse, out);
                out.add(new Request(Kind.CMD, cmd));
            } else {
                buffer.append(line).append('\n');
            }
        }
        return ignored + flush(buffer, skipProse, out);
    }

    /** '-- cmd:' lines anywhere; bare command lines only between statements. */
    private static String command(String line, boolean boundary) {
        Matcher m = CMD_COMMENT.matcher(line);
        if (m.matches()) {
            return m.group(1);
        }
        m = BARE_CMD.matcher(line);
        return boundary && m.matches() ? m.group(1).strip() : null;
    }

    /** True if the buffered SQL is empty or ends with a complete statement (ignoring comments). */
    private static boolean atStatementBoundary(StringBuilder buffer) {
        String[] lines = buffer.toString().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].strip();
            if (l.isEmpty() || l.startsWith("--")) {
                continue;
            }
            return l.endsWith(";");
        }
        return true;
    }

    private static int flush(StringBuilder buffer, boolean skipProse, List<Request> out) {
        int ignored = 0;
        for (String statement : splitStatements(buffer.toString())) {
            String sql = statement;
            if (skipProse && !isQuery(sql) && !WRITE_KEYWORDS.contains(firstWord(sql))) {
                Matcher m = QUERY_START.matcher(sql);
                if (!m.find()) {
                    ignored++;
                    continue;
                }
                sql = sql.substring(m.start()).strip();
            }
            out.add(new Request(Kind.SQL, sql));
        }
        buffer.setLength(0);
        return ignored;
    }

    /** True if the first word after leading comments is a read-query keyword. */
    static boolean isQuery(String sql) {
        return QUERY_KEYWORDS.contains(firstWord(sql));
    }

    private static String firstWord(String sql) {
        return stripLeadingComments(sql).split("[\\s(]+", 2)[0].toLowerCase(Locale.ROOT);
    }

    /** Removes leading whitespace, {@code --} line comments and {@code /* *\/} block comments. */
    public static String stripLeadingComments(String sql) {
        String s = sql;
        while (true) {
            s = s.stripLeading();
            if (s.startsWith("--")) {
                int nl = s.indexOf('\n');
                s = nl < 0 ? "" : s.substring(nl + 1);
            } else if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                s = end < 0 ? "" : s.substring(end + 2);
            } else {
                return s;
            }
        }
    }

    /** Splits on ';' outside quotes and comments; drops empty statements and comment-only ones. */
    static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean meaningful = false;
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = i + 1;
                while (end < n) {
                    if (sql.charAt(end) == c) {
                        if (end + 1 < n && sql.charAt(end + 1) == c) {
                            end += 2;
                            continue;
                        }
                        break;
                    }
                    end++;
                }
                current.append(sql, i, Math.min(end + 1, n));
                meaningful = true;
                i = end;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? n : end;
                current.append(sql, i, end);
                i = end - 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                current.append(sql, i, end);
                i = end - 1;
            } else if (c == ';') {
                add(out, current, meaningful);
                current.setLength(0);
                meaningful = false;
            } else {
                current.append(c);
                if (!Character.isWhitespace(c)) {
                    meaningful = true;
                }
            }
        }
        add(out, current, meaningful);
        return out;
    }

    private static void add(List<String> out, StringBuilder sb, boolean meaningful) {
        String s = sb.toString().strip();
        if (meaningful && !s.isEmpty()) {
            out.add(s);
        }
    }
}
