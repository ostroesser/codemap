package codemap.scan.maven;

import java.nio.file.Path;
import java.util.List;

/** One Maven module after inheritance and interpolation within the scanned workspace. */
public record PomModel(
        String repo,
        Path dir,
        String relativePath,
        String groupId,
        String artifactId,
        String version,
        String packaging,
        String parent,
        List<Dep> dependencies,
        List<String> problems) {

    public record Dep(String groupId, String artifactId, String version, String scope, boolean optional) {
    }

    public String ga() {
        return groupId + ":" + artifactId;
    }
}
