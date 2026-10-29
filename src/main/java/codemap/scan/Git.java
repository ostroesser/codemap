package codemap.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Minimal git CLI wrapper. Every method returns null when git or the repo is unavailable. */
public final class Git {

    public record State(String commit, boolean dirty, String statusText) {
    }

    private Git() {
    }

    public static State state(Path repoDir) {
        String commit = run(repoDir, "rev-parse", "HEAD");
        if (commit == null) {
            return null;
        }
        // Scoped to the directory: a repo may be a subfolder of a larger checkout.
        String status = run(repoDir, "status", "--porcelain", "--untracked-files=normal", "--", ".");
        return new State(commit.strip(), status != null && !status.isBlank(), status == null ? "" : status);
    }

    static String run(Path dir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(dir.toString());
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            p.getErrorStream().readAllBytes();
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
                p.destroyForcibly();
                return null;
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
