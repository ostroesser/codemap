package codemap;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.Callable;

import codemap.db.Database;
import codemap.report.ReportCommands;
import codemap.run.RunCommand;
import codemap.runtime.ImportActuatorCommand;
import codemap.scan.ScanCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "codemap", mixinStandardHelpOptions = true, version = "codemap 0.1.0",
        description = "Architecture fact database for Spring Boot codebases, queried by a chat LLM.",
        subcommands = {ScanCommand.class, ReportCommands.BriefCommand.class, ReportCommands.DigestCommand.class,
                RunCommand.class, ReportCommands.DiagramCommand.class, ImportActuatorCommand.class,
                Main.InitDb.class})
public final class Main implements Runnable {

    static {
        RunCommand.register("diagram", ReportCommands.DIAGRAM_VERB);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    @Command(name = "init-db", description = "Create an empty fact database with the current schema.")
    static final class InitDb implements Callable<Integer> {

        @Option(names = "--db", defaultValue = "codemap.db", description = "Database file (default: ${DEFAULT-VALUE})")
        Path db;

        @Override
        public Integer call() throws Exception {
            try (Connection ignored = Database.openForWrite(db)) {
                System.out.println("Schema v" + Database.SCHEMA_VERSION + " ready in " + db);
            }
            return 0;
        }
    }
}
