package codemap.run;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import codemap.db.Database;

/** Executes the requests of one LLM answer and renders the paste-back text. */
public final class Runner {

    public static final int DEFAULT_MAX_CHARS = 80_000;

    public record Result(String text, int queries, int commands, List<Integer> unitsShown) {
    }

    /** Extra cmd verbs (e.g. diagram) registered by other packages. */
    public interface Verb {
        Section run(Connection conn, String label, String args) throws Exception;
    }

    private final Path db;
    private final int maxChars;
    private final Map<String, Verb> verbs;

    public Runner(Path db, int maxChars, Map<String, Verb> verbs) {
        this.db = db;
        this.maxChars = maxChars;
        this.verbs = verbs;
    }

    /**
     * @param page        1 for a fresh run; N re-runs the same input showing rows after those
     *                    shown on earlier pages
     * @param firstPageShown rows shown per section on page 1 (null when page = 1)
     */
    public Result run(String input, int page, List<Integer> firstPageShown) throws SQLException {
        List<Blocks.Request> requests = Blocks.extract(input);
        if (requests.isEmpty()) {
            String msg = "codemap: no ```sql or ```cmd blocks found in the input.\n";
            return new Result(msg, 0, 0, List.of());
        }
        List<Section> sections = new ArrayList<>();
        int q = 0;
        int c = 0;
        try (Connection conn = Database.openReadOnly(db)) {
            SqlRunner sql = new SqlRunner(conn);
            for (int i = 0; i < requests.size(); i++) {
                Blocks.Request r = requests.get(i);
                if (r.kind() == Blocks.Kind.SQL) {
                    q++;
                    long offset = 0;
                    if (page > 1 && firstPageShown != null && i < firstPageShown.size()) {
                        offset = (long) (page - 1) * Math.max(1, firstPageShown.get(i));
                    }
                    sections.add(sql.run("Q" + q, r.text(), offset));
                } else {
                    c++;
                    sections.add(command(conn, "C" + c, r.text()));
                }
            }
        }
        String preamble = "## codemap results" + (page > 1 ? " · page " + page : "")
                + " · " + plural(q, "query", "queries") + (c > 0 ? ", " + plural(c, "command", "commands") : "");
        Budget.Rendered rendered = Budget.render(preamble, sections, maxChars, page);
        return new Result(rendered.text(), q, c, rendered.unitsShown());
    }

    private Section command(Connection conn, String label, String line) {
        String[] parts = line.split("\\s+", 2);
        String verb = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].strip() : "";
        try {
            if (verb.equals("source")) {
                if (args.isEmpty()) {
                    return usage(label, line, "source <Class> | source <Class#method> | source <Class#method(Types)>");
                }
                return new SourceViewer(conn).show(label, args);
            }
            Verb v = verbs.get(verb);
            if (v != null) {
                return v.run(conn, label, args);
            }
            return usage(label, line, "unknown command; available: source"
                    + (verbs.isEmpty() ? "" : ", " + String.join(", ", verbs.keySet())));
        } catch (Exception e) {
            return Section.text(label, List.of("### " + label + " · ERROR · `" + line + "`", "ERROR: " + e.getMessage()));
        }
    }

    private static Section usage(String label, String line, String message) {
        return Section.text(label, List.of("### " + label + " · ERROR · `" + line + "`", "ERROR: " + message));
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
