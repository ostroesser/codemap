package codemap.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code source Class} / {@code source Class#method} / {@code source Class#method(Types)}:
 * prints code from src/main/java. Bytecode line numbers cover executable lines only, so the
 * excerpt is widened to the declaration (with its annotations) and the closing brace.
 */
public final class SourceViewer {

    private static final int MAX_METHOD_LINES = 400;

    private final Connection conn;

    public SourceViewer(Connection conn) {
        this.conn = conn;
    }

    public Section show(String label, String arg) throws SQLException {
        String classPart = arg;
        String methodPart = null;
        int hash = arg.indexOf('#');
        if (hash >= 0) {
            classPart = arg.substring(0, hash);
            methodPart = arg.substring(hash + 1);
        }
        List<String[]> classes = findClass(classPart);
        if (classes.isEmpty()) {
            return error(label, arg, "no scanned class matches '" + classPart + "' (query classes by simple_name)");
        }
        if (classes.size() > 1) {
            List<String> names = classes.stream().map(c -> c[0]).toList();
            return error(label, arg, "ambiguous class '" + classPart + "': " + String.join(", ", names));
        }
        String fqn = classes.get(0)[0];
        String sourceFile = classes.get(0)[1];
        String repoPath = classes.get(0)[2];
        if (sourceFile == null) {
            return error(label, arg, "no source file under src/main/java for " + fqn);
        }
        Path file = Path.of(repoPath).resolve(sourceFile);
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return error(label, arg, "cannot read " + sourceFile + ": " + e.getMessage());
        }
        String repo = classes.get(0)[3];
        if (methodPart == null) {
            return excerpt(label, repo + "/" + sourceFile, lines, 1, lines.size());
        }

        List<String[]> methods = findMethods(fqn, methodPart);
        if (methods.isEmpty()) {
            return error(label, arg, "no method '" + methodPart + "' in " + fqn + " (query methods by class_fqn)");
        }
        List<String> fixed = List.of("### " + label + " · source · " + fqn + "#" + methodPart
                + (methods.size() > 1 ? " (" + methods.size() + " overloads)" : ""));
        List<String> units = new ArrayList<>();
        for (String[] m : methods) {
            int[] range = range(lines, m, simpleName(fqn));
            units.add("```java");
            units.add("// " + repo + "/" + sourceFile + ":" + range[0] + "-" + range[1]);
            units.addAll(lines.subList(range[0] - 1, range[1]));
            units.add("```");
        }
        return new Section(label, fixed, units, 0, "source lines", false);
    }

    private Section excerpt(String label, String path, List<String> lines, int from, int to) {
        List<String> fixed = new ArrayList<>();
        fixed.add("### " + label + " · source · " + path + ":" + from + "-" + to);
        fixed.add("```java");
        List<String> units = new ArrayList<>(lines.subList(from - 1, to));
        units.add("```");
        return new Section(label, fixed, units, 0, "source lines", false);
    }

    /** Declaration line (with annotations above) to the matching closing brace. */
    static int[] range(List<String> lines, String[] method, String classSimpleName) {
        String name = method[1].equals("<init>") ? classSimpleName : method[1];
        Pattern decl = Pattern.compile("(^|[\\s<>,\\]])" + Pattern.quote(name) + "\\s*\\(");
        int first = method[2] == null ? 0 : Integer.parseInt(method[2]);
        int start = -1;
        if (first > 0) {
            for (int i = Math.min(first, lines.size()); i >= Math.max(1, first - 15); i--) {
                if (decl.matcher(lines.get(i - 1)).find() && !lines.get(i - 1).strip().startsWith("//")) {
                    start = i;
                    break;
                }
            }
        }
        if (start < 0) {
            // abstract/interface method or unexpected layout: search the whole file
            for (int i = 1; i <= lines.size(); i++) {
                String l = lines.get(i - 1);
                if (decl.matcher(l).find() && !l.contains(" new ") && !l.strip().startsWith("return")
                        && !l.strip().startsWith("//") && !l.contains(".")) {
                    start = i;
                    break;
                }
            }
        }
        if (start < 0) {
            int s = Math.max(1, first);
            int e = Math.max(s, method[3] == null ? s : Integer.parseInt(method[3]));
            return new int[]{s, Math.min(lines.size(), e)};
        }
        while (start > 1 && lines.get(start - 2).strip().startsWith("@")) {
            start--;
        }
        int depth = 0;
        boolean opened = false;
        for (int i = start; i <= lines.size() && i < start + MAX_METHOD_LINES; i++) {
            String l = stripStringsAndComments(lines.get(i - 1));
            for (char ch : l.toCharArray()) {
                if (ch == '{') {
                    depth++;
                    opened = true;
                } else if (ch == '}') {
                    depth--;
                }
            }
            if ((opened && depth <= 0) || (!opened && l.strip().endsWith(";"))) {
                return new int[]{start, i};
            }
        }
        return new int[]{start, Math.min(lines.size(), start + MAX_METHOD_LINES - 1)};
    }

    private static String stripStringsAndComments(String line) {
        String l = line.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"").replaceAll("'(\\\\.|[^'\\\\])'", "''");
        int comment = l.indexOf("//");
        return comment >= 0 ? l.substring(0, comment) : l;
    }

    /** {fqn, source_file, repo path, repo}; by fqn, else by simple name. Nested classes map to their file. */
    private List<String[]> findClass(String name) throws SQLException {
        List<String[]> result = query("SELECT c.fqn, c.source_file, r.path, r.repo FROM classes c JOIN repos r"
                + " ON r.repo = c.repo WHERE c.fqn = ? AND c.is_external = 0", name);
        if (result.isEmpty()) {
            result = query("SELECT c.fqn, c.source_file, r.path, r.repo FROM classes c JOIN repos r ON r.repo = c.repo"
                    + " WHERE c.is_external = 0 AND (c.simple_name = ? OR c.fqn LIKE '%.' || ?)", name, name);
        }
        return result;
    }

    /** {method_id, name, first_line, last_line}; by exact id, by name(Types), or every overload. */
    private List<String[]> findMethods(String fqn, String method) throws SQLException {
        String sql = "SELECT method_id, name, first_line, last_line FROM methods WHERE class_fqn = ? AND ";
        if (method.contains("(")) {
            String id = fqn + "#" + method.replace(" ", "");
            return query(sql + "method_id = ?", fqn, id);
        }
        String name = method.equals(simpleName(fqn)) ? "<init>" : method;
        return query(sql + "name = ? ORDER BY first_line", fqn, name);
    }

    private static String simpleName(String fqn) {
        String s = fqn.substring(fqn.lastIndexOf('.') + 1);
        return s.substring(s.lastIndexOf('$') + 1);
    }

    private List<String[]> query(String sql, String... args) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
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

    private static Section error(String label, String arg, String message) {
        return Section.text(label, List.of("### " + label + " · ERROR · `source " + arg + "`", "ERROR: " + message));
    }
}
