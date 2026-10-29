package codemap.scan;

import static codemap.TestDb.one;
import static codemap.TestDb.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import codemap.db.Database;
import codemap.fixture.Fixture;

class ScannerTest {

    @TempDir
    static Path shared;

    static Path workspace;
    static Path db;

    @BeforeAll
    static void scanFixture() throws Exception {
        workspace = Fixture.gitCopy(shared.resolve("ws"));
        db = shared.resolve("codemap.db");
        Scanner.scan(db, List.of(workspace), options(false));
    }

    static Scanner.Options options(boolean force) {
        return new Scanner.Options("shop", force, new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    void findsReposAndModules() throws Exception {
        try (Connection c = Database.openReadOnly(db)) {
            assertEquals(List.of("catalog|shop|1", "orders|shop|3", "shop-common|shop|1"),
                    rows(c, "SELECT repo, app, module_count FROM repos ORDER BY repo"));
            assertEquals(List.of(
                            "catalog-service|catalog|com.example.shop|3.0.2|jar|.",
                            "orders-domain|orders|com.example.shop.orders|2.1.0-SNAPSHOT|jar|orders-domain",
                            "orders-parent|orders|com.example.shop.orders|2.1.0-SNAPSHOT|pom|.",
                            "orders-service|orders|com.example.shop.orders|2.1.0-SNAPSHOT|jar|orders-service",
                            "shop-common|shop-common|com.example.shop|1.4.0|jar|."),
                    rows(c, "SELECT module, repo, group_id, version, packaging, path FROM modules ORDER BY module"));
            assertEquals("com.example.shop.orders:orders-parent",
                    one(c, "SELECT parent FROM modules WHERE module = 'orders-domain'"));
            assertEquals("1", one(c, "SELECT has_classes FROM modules WHERE module = 'catalog-service'"));
            assertTrue(one(c, "SELECT git_commit FROM repos WHERE repo = 'orders'").matches("[0-9a-f]{40}"));
        }
    }

    @Test
    void resolvesVersionsFromPropertiesAndDependencyManagement() throws Exception {
        try (Connection c = Database.openReadOnly(db)) {
            assertEquals("1.4.0|compile|shop-common", one(c, "SELECT version, scope, internal_module"
                    + " FROM maven_dependencies WHERE module = 'orders-service' AND artifact_id = 'shop-common'"));
            assertEquals("2.1.0-SNAPSHOT|orders-domain", one(c, "SELECT version, internal_module"
                    + " FROM maven_dependencies WHERE module = 'orders-service' AND artifact_id = 'orders-domain'"));
            assertEquals("test", one(c, "SELECT scope FROM maven_dependencies"
                    + " WHERE module = 'orders-service' AND artifact_id = 'spring-boot-starter-test'"));
            assertEquals("0", one(c, "SELECT COUNT(*) FROM maven_dependencies"
                    + " WHERE artifact_id LIKE 'spring%' AND internal_module IS NOT NULL"));
        }
    }

    @Test
    void moduleLinksFlagCrossRepoDependencies() throws Exception {
        try (Connection c = Database.openReadOnly(db)) {
            assertEquals(List.of(
                            "catalog|catalog-service|shop-common|shop-common|1",
                            "orders|orders-service|orders|orders-domain|0",
                            "orders|orders-service|shop-common|shop-common|1"),
                    rows(c, "SELECT from_repo, from_module, to_repo, to_module, cross_repo FROM module_links"
                            + " ORDER BY from_module, to_module"));
        }
    }

    @Test
    void flattensConfigPerProfile() throws Exception {
        try (Connection c = Database.openReadOnly(db)) {
            assertEquals("orders", one(c, "SELECT value FROM config_properties WHERE module = 'orders-service'"
                    + " AND profile = '(default)' AND key = 'spring.application.name'"));
            assertEquals("kafka-1:9092,kafka-2:9092|application.yml", one(c, "SELECT value, file FROM config_properties"
                    + " WHERE module = 'orders-service' AND profile = 'prod' AND key = 'spring.kafka.bootstrap-servers'"));
            assertEquals("0 */5 * * * *", one(c, "SELECT value FROM config_properties WHERE module = 'orders-service'"
                    + " AND profile = 'dev' AND key = 'shop.jobs.stale-cron'"));
            assertEquals("DEBUG", one(c, "SELECT value FROM config_properties WHERE module = 'catalog-service'"
                    + " AND key = 'logging.level.com.example.shop'"));
            assertEquals("8081", one(c, "SELECT value FROM config_properties WHERE module = 'catalog-service'"
                    + " AND key = 'server.port'"));
        }
    }

    @Test
    void configEffectiveOverlaysProfileOnDefault() throws Exception {
        try (Connection c = Database.openReadOnly(db)) {
            assertEquals("dev.shop.orders", one(c, "SELECT value FROM config_effective WHERE module = 'orders-service'"
                    + " AND profile = 'dev' AND key = 'shop.topics.orders'"));
            assertEquals("8080", one(c, "SELECT value FROM config_effective WHERE module = 'orders-service'"
                    + " AND profile = 'dev' AND key = 'server.port'"));
            assertEquals("http://catalog:8080", one(c, "SELECT value FROM config_effective"
                    + " WHERE module = 'orders-service' AND profile = 'prod' AND key = 'shop.catalog.base-url'"));
            assertEquals("shop.orders.v1", one(c, "SELECT value FROM config_effective"
                    + " WHERE module = 'orders-service' AND profile = '(default)' AND key = 'shop.topics.orders'"));
        }
    }

    @Test
    void secondScanSkipsUnchangedReposAndRescansChangedOnes(@TempDir Path tmp) throws Exception {
        Path ws = Fixture.gitCopy(tmp.resolve("ws"));
        Path localDb = tmp.resolve("x.db");
        Scanner.scan(localDb, List.of(ws), options(false));

        var second = Scanner.scan(localDb, List.of(ws), options(false));
        assertTrue(second.stream().allMatch(Scanner.RepoReport::skipped), second.toString());

        Path yml = ws.resolve("catalog/src/main/resources/application.yml");
        Files.writeString(yml, Files.readString(yml).replace("8081", "9091"));
        var dirty = Scanner.scan(localDb, List.of(ws), options(false));
        assertFalse(report(dirty, "catalog").skipped(), "dirty repo is rescanned");
        assertTrue(report(dirty, "orders").skipped());

        Fixture.git(ws.resolve("catalog"), "commit", "-qam", "port");
        Scanner.scan(localDb, List.of(ws), options(false));
        assertTrue(report(Scanner.scan(localDb, List.of(ws), options(false)), "catalog").skipped());
        try (Connection c = Database.openReadOnly(localDb)) {
            assertEquals("9091", one(c, "SELECT value FROM config_properties WHERE module = 'catalog-service'"
                    + " AND key = 'server.port'"));
            assertEquals("1", one(c, "SELECT COUNT(*) FROM modules WHERE module = 'catalog-service'"));
        }

        var forced = Scanner.scan(localDb, List.of(ws), options(true));
        assertTrue(forced.stream().noneMatch(Scanner.RepoReport::skipped));
    }

    @Test
    void newBuildOutputTriggersRescan(@TempDir Path tmp) throws Exception {
        Path ws = Fixture.gitCopy(tmp.resolve("ws"));
        Path localDb = tmp.resolve("x.db");
        Path classes = ws.resolve("catalog/target/classes");
        Path moved = tmp.resolve("classes-backup");
        Files.move(classes, moved);

        Scanner.scan(localDb, List.of(ws), options(false));
        try (Connection c = Database.openReadOnly(localDb)) {
            assertEquals("catalog-service|no_classes", one(c, "SELECT module, kind FROM scan_warnings"
                    + " WHERE repo = 'catalog'"));
        }

        Files.move(moved, classes); // "mvn compile" happened, commit unchanged
        assertFalse(report(Scanner.scan(localDb, List.of(ws), options(false)), "catalog").skipped());
        try (Connection c = Database.openReadOnly(localDb)) {
            assertEquals("0", one(c, "SELECT COUNT(*) FROM scan_warnings WHERE repo = 'catalog'"));
        }
    }

    @Test
    void rescanKeepsRuntimeFacts(@TempDir Path tmp) throws Exception {
        Path ws = Fixture.gitCopy(tmp.resolve("ws"));
        Path localDb = tmp.resolve("x.db");
        Scanner.scan(localDb, List.of(ws), options(false));
        try (Connection c = Database.openForWrite(localDb); var st = c.createStatement()) {
            st.execute("INSERT INTO beans(service, bean_name, repo, source) VALUES ('catalog', 'x', 'catalog', 'runtime')");
            st.execute("INSERT INTO beans(service, bean_name, repo, source) VALUES ('catalog', 'y', 'catalog', 'static')");
        }
        Scanner.scan(localDb, List.of(ws), options(true));
        try (Connection c = Database.openReadOnly(localDb)) {
            assertEquals("x|runtime", one(c, "SELECT bean_name, source FROM beans"));
        }
    }

    @Test
    void acceptsSingleRepoPathsAndRejectsDuplicates(@TempDir Path tmp) throws Exception {
        var repos = Scanner.locateRepos(List.of(workspace.resolve("orders"), workspace.resolve("catalog")));
        assertEquals(List.of("orders", "catalog"), List.copyOf(repos.keySet()));
        Path other = tmp.resolve("x/catalog");
        Files.createDirectories(other);
        Files.writeString(other.resolve("pom.xml"), "<project/>");
        assertThrows(IOException.class, () -> Scanner.locateRepos(List.of(workspace, tmp.resolve("x"))));
        assertThrows(IOException.class, () -> Scanner.locateRepos(List.of(tmp)));
    }

    private static Scanner.RepoReport report(List<Scanner.RepoReport> reports, String repo) {
        return reports.stream().filter(r -> r.repo().equals(repo)).findFirst().orElseThrow();
    }
}
