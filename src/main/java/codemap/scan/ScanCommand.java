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

    @Option(names = "--config", description = "Local settings (default: codemap.local.yml if present)")
    Path config;

    @Option(names = "--classpath-from-maven", description = {
            "Run mvn dependency:build-classpath in each repo first, so annotations and base classes",
            "from dependency jars (e.g. custom stereotypes) are resolved. Needs mvn on PATH."})
    boolean classpathFromMaven;

    @Override
    public Integer call() throws Exception {
        Path configFile = config != null ? config : Path.of("codemap.local.yml");
        if (config != null && !java.nio.file.Files.isRegularFile(config)) {
            System.err.println("Config file not found: " + config);
            return 2;
        }
        LocalConfig local = LocalConfig.load(configFile);
        if (classpathFromMaven) {
            for (Path repo : Scanner.locateRepos(dirs).values()) {
                MavenClasspath.write(repo, System.err);
            }
        }
        var reports = Scanner.scan(db, dirs, new Scanner.Options(app, force, local, System.err));
        long scanned = reports.stream().filter(r -> !r.skipped()).count();
        System.err.printf("%d repos (%d scanned, %d unchanged) -> %s%n",
                reports.size(), scanned, reports.size() - scanned, db);
        return 0;
    }
}
