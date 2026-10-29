package codemap.scan.maven;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

/**
 * Reads raw POMs of all scanned repos, then resolves groupId/version inheritance, properties
 * and dependencyManagement along parent chains that stay inside the scanned workspace.
 * This is deliberately not a full effective POM: external parents and BOM imports are not
 * resolved; unresolved versions keep their {@code ${...}} expression.
 */
public final class PomReader {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private record Raw(String repo, Path repoDir, Path dir, Model model, String error) {
        String groupId() {
            if (model.getGroupId() != null) {
                return model.getGroupId();
            }
            return model.getParent() == null ? null : model.getParent().getGroupId();
        }

        String ga() {
            return groupId() + ":" + model.getArtifactId();
        }
    }

    private final List<Raw> raws = new ArrayList<>();
    private final Map<String, Raw> byGa = new HashMap<>();

    /** Reads the POM tree of one repo (root pom.xml plus declared modules, recursively). */
    public void addRepo(String repo, Path repoDir) {
        readTree(repo, repoDir, repoDir, 0);
    }

    private void readTree(String repo, Path repoDir, Path dir, int depth) {
        Path pom = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pom) || depth > 20) {
            return;
        }
        Model model;
        try (Reader r = Files.newBufferedReader(pom)) {
            model = new MavenXpp3Reader().read(r, false);
        } catch (Exception e) {
            raws.add(new Raw(repo, repoDir, dir, new Model(), "unreadable pom.xml: " + e.getMessage()));
            return;
        }
        Raw raw = new Raw(repo, repoDir, dir, model, null);
        raws.add(raw);
        byGa.putIfAbsent(raw.ga(), raw);
        for (String module : model.getModules()) {
            readTree(repo, repoDir, dir.resolve(module).normalize(), depth + 1);
        }
    }

    /** Resolves every POM read so far. */
    public List<PomModel> resolve() {
        List<PomModel> result = new ArrayList<>();
        for (Raw raw : raws) {
            result.add(resolve(raw));
        }
        return result;
    }

    private PomModel resolve(Raw raw) {
        String rel = raw.repoDir().relativize(raw.dir()).toString();
        if (rel.isEmpty()) {
            rel = ".";
        }
        if (raw.error() != null) {
            return new PomModel(raw.repo(), raw.dir(), rel, null, raw.dir().getFileName().toString(), null,
                    "pom", null, List.of(), List.of(raw.error()));
        }
        Model m = raw.model();
        List<String> problems = new ArrayList<>();
        List<Raw> chain = parentChain(raw); // root-most first, then ... raw itself last

        Map<String, String> props = new HashMap<>();
        for (Raw r : chain) {
            Properties p = r.model().getProperties();
            p.stringPropertyNames().forEach(k -> props.put(k, p.getProperty(k)));
        }
        String version = m.getVersion() != null ? m.getVersion()
                : m.getParent() != null ? m.getParent().getVersion() : null;
        String groupId = raw.groupId();
        props.put("project.groupId", groupId);
        props.put("project.artifactId", m.getArtifactId());
        props.put("project.version", version);
        props.put("pom.version", version);
        props.put("version", version);
        if (m.getParent() != null) {
            props.put("project.parent.version", m.getParent().getVersion());
            props.put("project.parent.groupId", m.getParent().getGroupId());
        }
        version = interpolate(version, props);
        props.put("project.version", version);

        Map<String, Dependency> managed = new HashMap<>();
        for (Raw r : chain) {
            if (r.model().getDependencyManagement() != null) {
                for (Dependency d : r.model().getDependencyManagement().getDependencies()) {
                    managed.put(interpolate(d.getGroupId(), props) + ":" + interpolate(d.getArtifactId(), props), d);
                }
            }
        }

        Map<String, PomModel.Dep> deps = new LinkedHashMap<>();
        for (Raw r : chain) {
            for (Dependency d : r.model().getDependencies()) {
                String g = interpolate(d.getGroupId(), props);
                String a = interpolate(d.getArtifactId(), props);
                Dependency md = managed.get(g + ":" + a);
                String v = d.getVersion() != null ? d.getVersion() : md != null ? md.getVersion() : null;
                String scope = d.getScope() != null ? d.getScope()
                        : md != null && md.getScope() != null ? md.getScope() : "compile";
                deps.put(g + ":" + a, new PomModel.Dep(g, a, interpolate(v, props), scope, d.isOptional()));
            }
        }

        String parent = m.getParent() == null ? null : m.getParent().getGroupId() + ":" + m.getParent().getArtifactId();
        String packaging = m.getPackaging() == null ? "jar" : m.getPackaging();
        return new PomModel(raw.repo(), raw.dir(), rel, groupId, m.getArtifactId(), version, packaging, parent,
                List.copyOf(deps.values()), problems);
    }

    private List<Raw> parentChain(Raw raw) {
        List<Raw> chain = new ArrayList<>();
        Raw current = raw;
        while (current != null && chain.size() < 20 && !chain.contains(current)) {
            chain.add(0, current);
            var parent = current.model().getParent();
            current = parent == null ? null : byGa.get(parent.getGroupId() + ":" + parent.getArtifactId());
        }
        return chain;
    }

    static String interpolate(String value, Map<String, String> props) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        String current = value;
        for (int round = 0; round < 10 && current.contains("${"); round++) {
            Matcher matcher = PLACEHOLDER.matcher(current);
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (matcher.find()) {
                String replacement = props.get(matcher.group(1));
                if (replacement != null) {
                    changed = true;
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
            }
            matcher.appendTail(sb);
            current = sb.toString();
            if (!changed) {
                break;
            }
        }
        return current;
    }

    /** Convenience for tests and single-repo use. */
    public static List<PomModel> read(String repo, Path repoDir) throws IOException {
        PomReader reader = new PomReader();
        reader.addRepo(repo, repoDir);
        return reader.resolve();
    }
}
