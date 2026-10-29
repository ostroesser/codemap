package codemap.scan;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "scan", description = {
        "Scan Maven repos (compiled with mvn compile) into the fact database.",
        "Each path is a repo (has pom.xml) or a folder whose direct subfolders are repos.",
        "Unchanged repos (same commit and build output) are skipped."})
public final class ScanCommand implements Callable<Integer> {

    @Parameters(arity = "1..*", paramLabel = "DIR", description = "Repo or workspace directories")
    List<Path> dirs;

    @Option(names = "--app", required = true, description = "Application name")
    String app;

    @Option(names = "--db", defaultValue = "codemap.db", description = "Database file (default: ${DEFAULT-VALUE})")
    Path db;

    @Option(names = "--force", description = "Rescan every repo even if unchanged")
    boolean force;

    @Override
    public Integer call() throws Exception {
        var reports = Scanner.scan(db, dirs, new Scanner.Options(app, force, System.err));
        long scanned = reports.stream().filter(r -> !r.skipped()).count();
        System.err.printf("%d repos (%d scanned, %d unchanged) -> %s%n",
                reports.size(), scanned, reports.size() - scanned, db);
        return 0;
    }
}
