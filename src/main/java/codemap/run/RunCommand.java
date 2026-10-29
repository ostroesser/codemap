package codemap.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import codemap.scan.bytecode.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "run", description = {
        "Run the ```sql / ```cmd blocks of an LLM answer and put paste-ready results on the clipboard.",
        "Input: the clipboard (default), a file, or '-' for stdin."})
public final class RunCommand implements Callable<Integer> {

    @Parameters(arity = "0..1", paramLabel = "FILE|-", description = "Read the answer from a file or stdin")
    String input;

    @Option(names = "--db", defaultValue = "codemap.db", description = "Database file (default: ${DEFAULT-VALUE})")
    Path db;

    @Option(names = "--page", defaultValue = "1", description = "Show the next rows of the previous run's queries")
    int page;

    @Option(names = "--max-chars", defaultValue = "" + Runner.DEFAULT_MAX_CHARS,
            description = "Output budget in characters (default: ${DEFAULT-VALUE})")
    int maxChars;

    @Option(names = "--stdout", description = "Print the results instead of copying them")
    boolean stdout;

    @Option(names = "--out", description = "Write the results to a file instead of the clipboard")
    Path out;

    /** Verbs contributed by other commands (diagram). */
    static final Map<String, Runner.Verb> VERBS = new LinkedHashMap<>();

    @Override
    public Integer call() throws Exception {
        if (page < 1) {
            System.err.println("--page starts at 1");
            return 2;
        }
        Path session = sessionFile(db);
        String text;
        List<Integer> firstPage = null;
        if (page > 1) {
            if (!Files.isRegularFile(session)) {
                System.err.println("No previous run to page through (" + session + ")");
                return 2;
            }
            Map<String, Object> saved = Json.parseObject(Files.readString(session, StandardCharsets.UTF_8));
            text = String.valueOf(saved.get("input"));
            firstPage = new ArrayList<>();
            for (Object o : (List<?>) saved.getOrDefault("shown", List.of())) {
                firstPage.add(((Number) o).intValue());
            }
        } else if (input == null) {
            text = Clipboard.read();
        } else if (input.equals("-")) {
            text = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        } else {
            text = Files.readString(Path.of(input), StandardCharsets.UTF_8);
        }

        Runner.Result result = new Runner(db, maxChars, VERBS).run(text, page, firstPage);
        if (page == 1 && result.queries() + result.commands() > 0) {
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("input", text);
            saved.put("shown", result.unitsShown());
            try {
                Files.writeString(session, Json.write(saved), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("note: could not save paging state: " + e.getMessage());
            }
        }

        String where;
        if (stdout) {
            System.out.print(result.text());
            where = "stdout";
        } else if (out != null) {
            Files.writeString(out, result.text(), StandardCharsets.UTF_8);
            where = out.toString();
        } else {
            Clipboard.write(result.text());
            where = "clipboard";
        }
        System.err.printf("%d %s, %d %s -> %,d chars to %s%n", result.queries(),
                result.queries() == 1 ? "query" : "queries", result.commands(),
                result.commands() == 1 ? "command" : "commands", result.text().length(), where);
        return result.queries() + result.commands() == 0 ? 1 : 0;
    }

    static Path sessionFile(Path db) {
        Path abs = db.toAbsolutePath();
        return abs.resolveSibling(abs.getFileName() + ".lastrun.json");
    }
}
