package codemap.scan;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import codemap.scan.bytecode.ClasspathIndex;

/** Writes target/codemap.classpath in every module of a repo via the Maven dependency plugin. */
public final class MavenClasspath {

    private MavenClasspath() {
    }

    public static void write(Path repoDir, PrintStream log) throws IOException, InterruptedException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> cmd = List.of(windows ? "mvn.cmd" : "mvn", "-q", "-B", "-o",
                "dependency:build-classpath",
                "-Dmdep.outputFile=${project.build.directory}/" + Path.of(ClasspathIndex.CLASSPATH_FILE).getFileName(),
                "-Dmdep.includeScope=compile");
        log.printf("%-30s mvn dependency:build-classpath (offline)%n", repoDir.getFileName());
        Process p = new ProcessBuilder(cmd).directory(repoDir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
            log.println("  warning: classpath not written for " + repoDir.getFileName()
                    + " (is every dependency in ~/.m2? try once without -o):\n" + out.strip());
        }
    }
}
