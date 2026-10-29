package codemap.runtime;

import static codemap.TestDb.one;
import static codemap.TestDb.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import codemap.ScannedFixture;
import codemap.db.Database;
import codemap.report.Brief;
import codemap.report.Digest;

class ActuatorImportTest {

    static final String P = "com.example.shop.";

    @TempDir
    static Path shared;

    static Path db;

    @BeforeAll
    static void importFixtures() throws Exception {
        db = shared.resolve("runtime.db");
        Files.copy(ScannedFixture.db(), db);
        try (Connection c = Database.openForWrite(db)) {
            new ActuatorImporter(c, "catalog").importDir(resource("catalog"));
            new ActuatorImporter(c, "orders").importDir(resource("orders"));
        }
    }

    static Path resource(String name) throws URISyntaxException {
        return Path.of(ActuatorImportTest.class.getResource("/actuator/" + name).toURI());
    }

    @Test
    void beansWithProxiesAndOriginsResolved() throws Exception {
        try (Connection c = Database.openReadOnly(db)) {
            assertEquals(List.of(
                            "catalogApplication|" + P + "catalog.CatalogApplication|runtime|application",
                            "jpaStockReservations|" + P + "catalog.app.JpaStockReservations|scan|component",
                            "kafkaTemplate|org.springframework.kafka.core.KafkaTemplate|auto_config|null",
                            "orderEventsListener|" + P + "catalog.messaging.OrderEventsListener|scan|listener",
                            "productController|" + P + "catalog.web.ProductController|scan|controller",
                            "productRepository|" + P + "catalog.domain.ProductRepository|data_repository|repository",
                            "productService|" + P + "catalog.app.ProductService|scan|service"),
                    rows(c, "SELECT bean_name, class_fqn, declared_by, role FROM beans"
                            + " WHERE service = 'catalog' AND source = 'runtime' ORDER BY bean_name"));
        }
    }

    @Test
    void runtimeWiringShowsTheRealImplementation() throws Exception {
        try (Connection c = Database.openReadOnly(db)) {
            assertEquals(List.of("jpaStockReservations|" + P + "catalog.app.JpaStockReservations",
                            "productRepository|" + P + "catalog.domain.ProductRepository"),
                    rows(c, "SELECT member, resolved_class FROM injections_effective WHERE service = 'catalog'"
                            + " AND consumer_class = '" + P + "catalog.app.ProductService' ORDER BY member"));
            assertEquals("runtime", one(c, "SELECT DISTINCT source FROM injections_effective WHERE service = 'catalog'"));
            assertEquals("static", one(c, "SELECT DISTINCT source FROM injections_effective WHERE service = 'orders'"));
        }
    }

    @Test
    void compareViewsShowDifferences() throws Exception {
        try (Connection c = Database.openReadOnly(db)) {
            assertEquals(List.of("both|6", "runtime_only|1", "static_only|1"),
                    rows(c, "SELECT presence, COUNT(*) FROM beans_compare WHERE service = 'catalog'"
                            + " GROUP BY presence ORDER BY presence"));
            assertEquals("loggingStockReservations", one(c, "SELECT bean_name FROM beans_compare"
                    + " WHERE service = 'catalog' AND presence = 'static_only'"));
            assertEquals(List.of(
                            "null|/error|runtime_only",
                            "GET|/products|both",
                            "GET|/products/{}|both",
                            "POST|/products/{}/reservations|static_only"),
                    rows(c, "SELECT http_method, path_key, presence FROM endpoints_compare WHERE service = 'catalog'"
                            + " ORDER BY path_key, http_method"));
        }
    }

    @Test
    void endpointsFromMappings() throws Exception {
        try (Connection c = Database.openReadOnly(db)) {
            assertEquals(List.of("GET|/products|" + P + "catalog.web.ProductController#list(String)|application/json",
                            "GET|/products/{id}|" + P + "catalog.web.ProductController#get(long)|null"),
                    rows(c, "SELECT http_method, path, method_id, produces FROM endpoints WHERE source = 'runtime'"
                            + " AND class_fqn LIKE 'com.example%' ORDER BY path"));
            assertEquals("3", one(c, "SELECT COUNT(*) FROM endpoints_effective WHERE service = 'catalog'"));
            assertEquals("2", one(c, "SELECT COUNT(*) FROM endpoints_effective WHERE service = 'orders'"));
        }
    }

    @Test
    void configpropsAsRuntimeConfigInCanonicalForm() throws Exception {
        try (Connection c = Database.openReadOnly(db)) {
            assertEquals(List.of(
                            "server.port|8081",
                            "server.servlet.application-display-name|application",
                            "spring.kafka.bootstrap-servers|broker-1:9092,broker-2:9092",
                            "spring.kafka.consumer.auto-offset-reset|earliest",
                            "spring.kafka.consumer.group-id|catalog",
                            "spring.kafka.properties.sasl.jaas.config|******"),
                    rows(c, "SELECT key, value FROM config_effective WHERE source = 'runtime'"
                            + " AND module = 'catalog-service' ORDER BY key"));
            assertEquals("(runtime)", one(c, "SELECT DISTINCT profile FROM config_properties WHERE source = 'runtime'"));
        }
    }

    @Test
    void conditionsAndScheduledTasks() throws Exception {
        try (Connection c = Database.openReadOnly(db)) {
            assertEquals(List.of("KafkaAutoConfiguration|positive", "RabbitAutoConfiguration|negative",
                            "org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration|unconditional"),
                    rows(c, "SELECT condition_source, outcome FROM conditions WHERE service = 'catalog' ORDER BY 1"));
            assertTrue(one(c, "SELECT message FROM conditions WHERE outcome = 'negative'")
                    .startsWith("OnClassCondition: @ConditionalOnClass did not find"));
            // runtime schedule replaces the static one; static event listener of the same service stays
            assertEquals(List.of("event|static|null", "scheduled|runtime|cron=0 */5 * * * *"),
                    rows(c, "SELECT kind, source, schedule FROM listeners_effective WHERE service = 'orders' ORDER BY kind"));
            assertEquals(P + "orders.app.StaleOrderJob#purgeStaleOrders()", one(c,
                    "SELECT method_id FROM listeners WHERE source = 'runtime'"));
        }
    }

    @Test
    void reimportReplacesAndRescanKeeps(@TempDir Path tmp) throws Exception {
        Path local = tmp.resolve("x.db");
        Files.copy(db, local);
        try (Connection c = Database.openForWrite(local)) {
            new ActuatorImporter(c, "catalog").importDir(resource("catalog"));
            assertEquals("7", one(c, "SELECT COUNT(*) FROM beans WHERE source = 'runtime' AND service = 'catalog'"));
        }
    }

    @Test
    void contextPathFromConfigpropsPrefixesEndpoints(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("dump"));
        Files.copy(resource("catalog").resolve("mappings.json"), dir.resolve("mappings.json"));
        Files.writeString(dir.resolve("configprops.json"), """
                {"contexts": {"catalog": {"beans": {"server": {"prefix": "server",
                  "properties": {"servlet": {"contextPath": "/catalog-api"}}}}}}}
                """);
        Path local = tmp.resolve("x.db");
        Files.copy(ScannedFixture.db(), local);
        try (Connection c = Database.openForWrite(local)) {
            var report = new ActuatorImporter(c, "catalog").importDir(dir);
            assertEquals(2, report.rowsByFile().size());
            assertEquals("/catalog-api/products/{id}", one(c, "SELECT path FROM endpoints WHERE source = 'runtime'"
                    + " AND method_id LIKE '%#get(long)'"));
        }
    }

    @Test
    void unknownServiceAndEmptyDirAreReported(@TempDir Path tmp) throws Exception {
        Path local = tmp.resolve("x.db");
        Files.copy(ScannedFixture.db(), local);
        try (Connection c = Database.openForWrite(local)) {
            var report = new ActuatorImporter(c, "nope").importDir(Files.createDirectories(tmp.resolve("empty")));
            assertTrue(report.rowsByFile().isEmpty());
            assertEquals(2, report.notes().size(), report.notes().toString());
        }
    }

    @Test
    void briefAndDigestMentionRuntimeFacts() throws Exception {
        try (Connection c = Database.openReadOnly(db)) {
            String brief = new Brief(c).render();
            assertTrue(brief.contains("plus runtime actuator data for services catalog"), brief);
            assertTrue(brief.length() <= Brief.MAX_CHARS);
            String digest = new Digest(c).render("shop", Digest.MAX_CHARS);
            assertTrue(digest.contains("## Runtime facts"), digest);
            assertTrue(digest.contains("- beans: 6 both, 1 runtime_only, 1 static_only"), digest);
            assertTrue(digest.contains("not served at runtime: POST /products/{id}/reservations"), digest);
            assertTrue(digest.contains("- auto-configuration: 1 matched, 1 not matched"), digest);
        }
    }

    @Test
    void helpers() {
        assertEquals("bootstrap-servers", ActuatorImporter.kebab("bootstrapServers"));
        assertEquals("a.b-c", ActuatorImporter.kebab("a.bC"));
        assertEquals("com.x.Foo", ActuatorImporter.beanClass("com.x.Foo$$SpringCGLIB$$0", null));
        assertEquals("com.x.Repo", ActuatorImporter.beanClass("jdk.proxy2.$Proxy9", "com.x.Repo defined in @EnableJpaRepositories ..."));
    }
}
