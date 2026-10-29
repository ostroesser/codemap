package codemap.report;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import codemap.db.Database;
import codemap.run.Clipboard;
import codemap.run.Runner;
import codemap.run.Section;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/** brief, digest and diagram commands; all print to stdout unless --copy or --out. */
public final class ReportCommands {

    private ReportCommands() {
    }

    /** Shared output options. */
    public static final class Output {
        @Option(names = "--db", defaultValue = "codemap.db", description = "Database file (default: ${DEFAULT-VALUE})")
        Path db;

        @Option(names = "--copy", description = "Copy to the clipboard instead of printing")
        boolean copy;

        @Option(names = "--out", description = "Write to a file instead of printing")
        Path out;

        void emit(String text, String what) throws Exception {
            if (copy) {
                Clipboard.write(text);
                System.err.printf("%s: %,d chars copied to clipboard%n", what, text.length());
            } else if (out != null) {
                Files.writeString(out, text, StandardCharsets.UTF_8);
                System.err.printf("%s: %,d chars -> %s%n", what, text.length(), out);
            } else {
                System.out.print(text);
            }
        }
    }

    @Command(name = "brief", description = "LLM instructions and schema, to paste once at the start of a chat (<= 15k chars).")
    public static final class BriefCommand implements Callable<Integer> {
        @Mixin
        Output output;

        @Override
        public Integer call() throws Exception {
            try (Connection conn = Database.openReadOnly(output.db)) {
                output.emit(new Brief(conn).render(), "brief");
            }
            return 0;
        }
    }

    @Command(name = "digest", description = "Application map to paste after the brief (<= 80k chars, summarised when larger).")
    public static final class DigestCommand implements Callable<Integer> {
        @Mixin
        Output output;

        @Option(names = "--app", required = true, description = "Application name used at scan")
        String app;

        @Option(names = "--max-chars", defaultValue = "" + Digest.MAX_CHARS, description = "Budget (default: ${DEFAULT-VALUE})")
        int maxChars;

        @Override
        public Integer call() throws Exception {
            try (Connection conn = Database.openReadOnly(output.db)) {
                output.emit(new Digest(conn).render(app, maxChars), "digest");
            }
            return 0;
        }
    }

    @Command(name = "diagram", description = "PlantUML (C4) diagram of a repo, or of the whole application.")
    public static final class DiagramCommand implements Callable<Integer> {
        @Mixin
        Output output;

        @Option(names = "--repo", description = "Repo to draw")
        String repo;

        @Option(names = "--app", description = "Draw the application overview instead")
        String app;

        @Option(names = "--kind", defaultValue = "components", description = "components | artifacts (default: ${DEFAULT-VALUE})")
        String kind;

        @Override
        public Integer call() throws Exception {
            if ((repo == null) == (app == null)) {
                System.err.println("Give either --repo or --app");
                return 2;
            }
            try (Connection conn = Database.openReadOnly(output.db)) {
                Diagrams d = new Diagrams(conn);
                String uml = app != null ? d.overview(app) : switch (kind) {
                    case "components" -> d.components(repo);
                    case "artifacts" -> d.artifacts(repo);
                    default -> throw new IllegalArgumentException("--kind is components or artifacts");
                };
                output.emit(uml, "diagram");
            }
            return 0;
        }
    }

    /** {@code diagram <repo> [components|artifacts]} and {@code diagram app [name]} in cmd blocks. */
    public static final Runner.Verb DIAGRAM_VERB = (conn, label, args) -> {
        String[] parts = args.isBlank() ? new String[0] : args.strip().split("\\s+");
        if (parts.length == 0) {
            return Section.text(label, List.of("### " + label + " · ERROR · `diagram`",
                    "ERROR: usage: diagram <repo> [components|artifacts] | diagram app"));
        }
        Diagrams d = new Diagrams(conn);
        String uml;
        if (parts[0].equals("app")) {
            String app = parts.length > 1 ? parts[1] : new Db(conn).one("SELECT app FROM repos ORDER BY app LIMIT 1");
            uml = d.overview(app);
        } else {
            String kind = parts.length > 1 ? parts[1] : "components";
            if (new Db(conn).count("SELECT COUNT(*) FROM repos WHERE repo = ?", parts[0]) == 0) {
                return Section.text(label, List.of("### " + label + " · ERROR · `diagram " + args + "`",
                        "ERROR: unknown repo '" + parts[0] + "' (SELECT repo FROM repos)"));
            }
            uml = kind.equals("artifacts") ? d.artifacts(parts[0]) : d.components(parts[0]);
        }
        List<String> units = new ArrayList<>(uml.lines().toList());
        units.add("```");
        return new Section(label, List.of("### " + label + " · diagram " + args, "```plantuml"), units, 0,
                "diagram lines", false);
    };
}
