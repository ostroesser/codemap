package codemap.scan;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import codemap.scan.bytecode.ClasspathIndex;

/**
 * Writes target/codemap.classpath in every module of a repo via the Maven dependency plugin.
 * Tries offline first (fast, no network); retries online if the plugin or a dependency is not
 * in the local repository yet.
 */
public final class MavenClasspath {

    private MavenClasspath() {
    }

    public static boolean write(Path repoDir, PrintStream log) throws IOException, InterruptedException {
        log.printf("%-30s mvn dependency:build-classpath%n", repoDir.getFileName());
        String offlineOutput = run(repoDir, true);
        if (offlineOutput == null) {
            return true;
        }
        log.println("  offline attempt failed, retrying online");
        String onlineOutput = run(repoDir, false);
        if (onlineOutput == null) {
            return true;
        }
        log.println("  warning: classpath not written for " + repoDir.getFileName() + ":\n" + onlineOutput.strip());
        return false;
    }

    /** Returns null on success, else Maven's output. */
    private static String run(Path repoDir, boolean offline) throws IOException, InterruptedException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>(List.of(windows ? "mvn.cmd" : "mvn", "-q", "-B"));
        if (offline) {
            cmd.add("-o");
        }
        cmd.addAll(List.of(
                "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
                "-Dmdep.outputFile=${project.build.directory}/" + Path.of(ClasspathIndex.CLASSPATH_FILE).getFileName(),
                "-Dmdep.includeScope=compile"));
        Process p = new ProcessBuilder(cmd).directory(repoDir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(15, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return "timed out";
        }
        return p.exitValue() == 0 ? null : out;
    }
}
