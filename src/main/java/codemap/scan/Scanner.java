package codemap.scan;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import codemap.db.BatchInserter;
import codemap.db.Database;
import codemap.scan.bytecode.BytecodeScanner;
import codemap.scan.bytecode.ClasspathIndex;
import codemap.scan.bytecode.CodeModel;
import codemap.scan.bytecode.Roles;
import codemap.scan.spring.Meta;
import codemap.scan.spring.SpringModel;
import codemap.scan.spring.SpringScanner;
import codemap.scan.config.ConfigFiles;
import codemap.scan.maven.PomModel;
import codemap.scan.maven.PomReader;

/**
 * Scans repos into the fact database. POMs of all repos are always read (cheap, and needed
 * for cross-repo inheritance); everything else is only redone for repos whose fingerprint
 * (git commit + build output stamp) changed. Dirty or non-git repos are always rescanned.
 */
public final class Scanner {

    public record Options(String app, boolean force, LocalConfig config, PrintStream log) {
    }

    public record RepoReport(String repo, boolean skipped, int modules, int warnings) {
    }

    /** Tables whose rows belong to a class rather than carrying a repo column. */
    private static final Map<String, String> CLASS_KEYED = new LinkedHashMap<>();

    static {
        CLASS_KEYED.put("type_hierarchy", "class_fqn");
        CLASS_KEYED.put("annotations", "class_fqn");
        CLASS_KEYED.put("fields", "class_fqn");
        CLASS_KEYED.put("methods", "class_fqn");
        CLASS_KEYED.put("calls", "caller_class");
        CLASS_KEYED.put("class_dependencies", "from_class");
        CLASS_KEYED.put("entity_relations", "entity_fqn");
    }

    private final Connection conn;
    private final Options options;
    private Set<String> internalFqns;
    private Map<String, BytecodeScanner.Owner> owners;
    /** Modules rescanned in this run: {repo, module key, module dir}. */
    private final List<Object[]> changedModules = new ArrayList<>();

    public Scanner(Connection conn, Options options) {
        this.conn = conn;
        this.options = options;
    }

    public static List<RepoReport> scan(Path db, List<Path> inputs, Options options) throws SQLException, IOException {
        try (Connection conn = Database.openForWrite(db)) {
            return new Scanner(conn, options).scan(inputs);
        }
    }

    public List<RepoReport> scan(List<Path> inputs) throws SQLException, IOException {
        Map<String, Path> repos = locateRepos(inputs);
        PomReader poms = new PomReader();
        repos.forEach(poms::addRepo);
        List<PomModel> models = poms.resolve();
        Map<PomModel, String> moduleKeys = assignModuleKeys(models);
        internalFqns = new HashSet<>();
        for (PomModel m : models) {
            internalFqns.addAll(BytecodeScanner.classNames(m.dir().resolve("target/classes")));
        }
        owners = loadOwners();
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM scan_warnings WHERE repo IS NULL");
        }

        List<RepoReport> reports = new ArrayList<>();
        for (var repo : repos.entrySet()) {
            List<PomModel> repoModels = models.stream().filter(m -> m.repo().equals(repo.getKey())).toList();
            reports.add(scanRepo(repo.getKey(), repo.getValue(), repoModels, moduleKeys));
        }
        linkInternalModules();
        rebuildCodeModel(models);
        return reports;
    }

    /** Workspace-wide passes over all repos, changed or not. */
    private void rebuildCodeModel(List<PomModel> models) throws SQLException, IOException {
        try (ClasspathIndex classpath = new ClasspathIndex()) {
            int files = 0;
            for (PomModel m : models) {
                if (classpath.addClasspathFile(m.dir())) {
                    files++;
                }
            }
            if (files == 0) {
                String msg = "no " + ClasspathIndex.CLASSPATH_FILE + " files: meta-annotations of library annotations"
                        + " fall back to built-in Spring knowledge (use --classpath-from-maven)";
                options.log().println("  note: " + msg);
                try (var w = new BatchInserter(conn, "scan_warnings", "repo", "module", "kind", "message")) {
                    w.add(null, null, "no_classpath", msg);
                }
            }
            CodeModel.rebuild(conn, classpath);
        }
        Roles.assign(conn, options.config());
        scanSpring();
        SpringModel.rebuild(conn);
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE repos SET class_count = (SELECT COUNT(*) FROM classes c"
                    + " WHERE c.repo = repos.repo AND c.is_external = 0)");
        }
    }

    /** Module-scoped Spring facts for rescanned modules; needs the meta-annotation closure. */
    private void scanSpring() throws SQLException, IOException {
        Meta meta = Meta.load(conn);
        SpringScanner spring = new SpringScanner(conn, meta, options.config());
        conn.setAutoCommit(false);
        try {
            for (Object[] m : changedModules) {
                String module = (String) m[1];
                Path dir = (Path) m[2];
                spring.scanModule((String) m[0], module, dir, BytecodeScanner.readModule(dir, module, owners));
            }
            conn.commit();
        } catch (SQLException | IOException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private Map<String, BytecodeScanner.Owner> loadOwners() throws SQLException {
        Map<String, BytecodeScanner.Owner> result = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT fqn, repo, module FROM classes WHERE is_external = 0")) {
            while (rs.next()) {
                result.put(rs.getString(1), new BytecodeScanner.Owner(rs.getString(2), rs.getString(3)));
            }
        }
        return result;
    }

    // ── repo discovery ────────────────────────────────────────────────

    static Map<String, Path> locateRepos(List<Path> inputs) throws IOException {
        Map<String, Path> repos = new LinkedHashMap<>();
        for (Path input : inputs) {
            Path dir = input.toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                throw new IOException("Not a directory: " + input);
            }
            List<Path> found = new ArrayList<>();
            if (Files.isRegularFile(dir.resolve("pom.xml"))) {
                found.add(dir);
            } else {
                try (Stream<Path> children = Files.list(dir)) {
                    children.filter(Files::isDirectory)
                            .filter(c -> Files.isRegularFile(c.resolve("pom.xml")))
                            .sorted()
                            .forEach(found::add);
                }
            }
            if (found.isEmpty()) {
                throw new IOException("No pom.xml in " + input + " or its direct subdirectories");
            }
            for (Path repoDir : found) {
                String name = repoDir.getFileName().toString();
                Path previous = repos.putIfAbsent(name, repoDir);
                if (previous != null && !previous.equals(repoDir)) {
                    throw new IOException("Two repos named '" + name + "': " + previous + " and " + repoDir);
                }
            }
        }
        return repos;
    }

    /** Module key = artifactId; on a clash within the workspace, groupId:artifactId. */
    static Map<PomModel, String> assignModuleKeys(List<PomModel> models) {
        Map<String, Integer> counts = new HashMap<>();
        models.forEach(m -> counts.merge(m.artifactId(), 1, Integer::sum));
        Map<PomModel, String> keys = new HashMap<>();
        for (PomModel m : models) {
            keys.put(m, counts.get(m.artifactId()) > 1 ? m.ga() : m.artifactId());
        }
        return keys;
    }

    // ── per repo ───────────────────────────────────────────────────────

    private RepoReport scanRepo(String repo, Path dir, List<PomModel> models, Map<PomModel, String> keys)
            throws SQLException, IOException {
        Git.State git = Git.state(dir);
        String fingerprint = git == null || git.dirty() ? null : fingerprint(git.commit(), models);
        if (!options.force() && fingerprint != null && fingerprint.equals(storedFingerprint(repo))) {
            options.log().printf("%-30s unchanged, skipped%n", repo);
            return new RepoReport(repo, true, models.size(), 0);
        }

        conn.setAutoCommit(false);
        try {
            deleteRepo(repo);
            owners.values().removeIf(o -> o.repo().equals(repo));
            List<String[]> warnings = new ArrayList<>(); // module, kind, message
            try (var mod = new BatchInserter(conn, "modules", "module", "repo", "group_id", "artifact_id",
                    "version", "packaging", "parent", "path", "has_classes", "is_service");
                 var dep = new BatchInserter(conn, "maven_dependencies", "module", "repo", "group_id",
                         "artifact_id", "version", "scope", "optional");
                 var cfg = new BatchInserter(conn, "config_properties", "repo", "module", "file", "profile",
                         "key", "value", "source")) {
                for (PomModel m : models) {
                    String key = keys.get(m);
                    m.problems().forEach(p -> warnings.add(new String[]{key, "pom", p}));
                    boolean hasClasses = hasClasses(m.dir());
                    if (!hasClasses && expectsClasses(m)) {
                        warnings.add(new String[]{key, "no_classes",
                                m.relativePath() + " has sources but no target/classes; run mvn compile"});
                    }
                    if (!key.equals(m.artifactId())) {
                        warnings.add(new String[]{key, "duplicate_module",
                                "artifactId " + m.artifactId() + " exists in several repos; module key is " + key});
                    }
                    mod.add(key, repo, m.groupId(), m.artifactId(), m.version(), m.packaging(), m.parent(),
                            m.relativePath(), hasClasses, false);
                    for (PomModel.Dep d : m.dependencies()) {
                        dep.add(key, repo, d.groupId(), d.artifactId(), d.version(), d.scope(), d.optional());
                    }
                    ConfigFiles.Result config = ConfigFiles.scanModule(m.dir());
                    config.problems().forEach(p -> warnings.add(new String[]{key, "config", p}));
                    for (ConfigFiles.Entry e : config.entries()) {
                        cfg.add(repo, key, e.file(), e.profile(), e.key(), e.value(), "static");
                    }
                }
            }
            for (PomModel m : models) {
                String key = keys.get(m);
                BytecodeScanner bytecode = new BytecodeScanner(conn, options.config(), internalFqns, owners,
                        msg -> warnings.add(new String[]{key, "duplicate_class", msg}));
                bytecode.scanModule(repo, key, dir, m.dir());
                changedModules.add(new Object[]{repo, key, m.dir()});
            }
            if (git == null) {
                warnings.add(new String[]{null, "no_git", "not a git repo; it is rescanned every time"});
            }
            try (var w = new BatchInserter(conn, "scan_warnings", "repo", "module", "kind", "message")) {
                for (String[] warning : warnings) {
                    w.add(repo, warning[0], warning[1], warning[2]);
                }
            }
            try (var r = new BatchInserter(conn, "repos", "repo", "app", "path", "git_commit", "git_dirty",
                    "fingerprint", "scanned_at", "module_count", "class_count")) {
                r.add(repo, options.app(), dir.toString(), git == null ? null : git.commit(),
                        git == null ? null : git.dirty(), fingerprint,
                        Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), models.size(), 0);
            }
            conn.commit();
            for (String[] warning : warnings) {
                if (!warning[1].equals("no_git")) {
                    options.log().printf("  warning %s [%s] %s%n", repo, warning[1], warning[2]);
                }
            }
            options.log().printf("%-30s scanned: %d modules, %d warnings%n", repo, models.size(), warnings.size());
            return new RepoReport(repo, false, models.size(), warnings.size());
        } catch (SQLException | IOException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /** Removes everything a previous static scan stored for this repo. Runtime facts survive. */
    void deleteRepo(String repo) throws SQLException {
        for (var e : CLASS_KEYED.entrySet()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + e.getKey() + " WHERE "
                    + e.getValue() + " IN (SELECT fqn FROM classes WHERE repo = ?)")) {
                ps.setString(1, repo);
                ps.executeUpdate();
            }
        }
        for (String table : tablesWithColumn("repo")) {
            String sql = "DELETE FROM " + table + " WHERE repo = ?"
                    + (tablesWithColumn("source").contains(table) ? " AND source = 'static'" : "");
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, repo);
                ps.executeUpdate();
            }
        }
    }

    private List<String> tablesWithColumn(String column) throws SQLException {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT m.name FROM sqlite_master m JOIN pragma_table_info(m.name) c"
                + " WHERE m.type = 'table' AND c.name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, column);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        return tables;
    }

    private String storedFingerprint(String repo) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT fingerprint FROM repos WHERE repo = ? AND app = ?")) {
            ps.setString(1, repo);
            ps.setString(2, options.app());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Fills maven_dependencies.internal_module for every repo (links may target any repo). */
    private void linkInternalModules() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE maven_dependencies SET internal_module = (SELECT m.module FROM modules m"
                    + " WHERE m.group_id = maven_dependencies.group_id"
                    + " AND m.artifact_id = maven_dependencies.artifact_id)");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    static boolean hasClasses(Path moduleDir) {
        Path classes = moduleDir.resolve("target/classes");
        if (!Files.isDirectory(classes)) {
            return false;
        }
        try (Stream<Path> s = Files.walk(classes)) {
            return s.anyMatch(p -> p.toString().endsWith(".class"));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean expectsClasses(PomModel m) {
        return !"pom".equals(m.packaging()) && Files.isDirectory(m.dir().resolve("src/main/java"));
    }

    /** Commit plus, per module, the number and newest mtime of files in target/classes. */
    static String fingerprint(String commit, List<PomModel> models) {
        StringBuilder sb = new StringBuilder(commit);
        for (PomModel m : models) {
            Path classes = m.dir().resolve("target/classes");
            long count = 0;
            long newest = 0;
            if (Files.isDirectory(classes)) {
                try (Stream<Path> s = Files.walk(classes)) {
                    for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                        count++;
                        newest = Math.max(newest, Files.getLastModifiedTime(p).toMillis());
                    }
                } catch (IOException e) {
                    return null;
                }
            }
            sb.append('|').append(m.relativePath()).append(':').append(count).append(':').append(newest);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
