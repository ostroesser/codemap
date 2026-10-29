package codemap.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extracts the requests from an LLM answer: every fenced {@code ```sql} block (split into
 * statements) and every line of every {@code ```cmd} block, in order of appearance.
 * Text without any fence that starts like a query is taken as one SQL block.
 */
public final class Blocks {

    public enum Kind { SQL, CMD }

    public record Request(Kind kind, String text) {
    }

    private Blocks() {
    }

    public static List<Request> extract(String input) {
        List<Request> requests = new ArrayList<>();
        String[] lines = input.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean sawFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            String fence = line.startsWith("```") ? "```" : line.startsWith("~~~") ? "~~~" : null;
            if (fence == null) {
                continue;
            }
            sawFence = true;
            String lang = line.substring(3).strip().toLowerCase(Locale.ROOT);
            StringBuilder body = new StringBuilder();
            int j = i + 1;
            while (j < lines.length && !lines[j].strip().startsWith(fence)) {
                body.append(lines[j]).append('\n');
                j++;
            }
            if (lang.equals("sql") || lang.equals("sqlite")) {
                for (String statement : splitStatements(body.toString())) {
                    requests.add(new Request(Kind.SQL, statement));
                }
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
        if (!sawFence) {
            String trimmed = input.strip().toLowerCase(Locale.ROOT);
            if (trimmed.startsWith("select") || trimmed.startsWith("with")) {
                for (String statement : splitStatements(input)) {
                    requests.add(new Request(Kind.SQL, statement));
                }
            }
        }
        return requests;
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
