package codemap.runtime;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.Callable;

import codemap.db.Database;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "import-actuator", description = {
        "Load actuator JSON dumps of a running service as runtime facts (source = 'runtime').",
        "DIR holds any of: beans.json, mappings.json, conditions.json, configprops.json, scheduledtasks.json.",
        "See docs/runtime-facts.md for a local-only profile that exposes them."})
public final class ImportActuatorCommand implements Callable<Integer> {

    @Option(names = "--service", required = true, description = "Service name (as in the services table)")
    String service;

    @Option(names = "--db", defaultValue = "codemap.db", description = "Database file (default: ${DEFAULT-VALUE})")
    Path db;

    @Parameters(paramLabel = "DIR", description = "Directory with the captured JSON files")
    Path dir;

    @Override
    public Integer call() throws Exception {
        try (Connection conn = Database.openForWrite(db)) {
            ActuatorImporter.Report report = new ActuatorImporter(conn, service).importDir(dir);
            report.notes().forEach(n -> System.err.println("note: " + n));
            report.rowsByFile().forEach((file, rows) -> System.err.printf("%-22s %d rows%n", file, rows));
            return report.rowsByFile().isEmpty() ? 1 : 0;
        }
    }
}
