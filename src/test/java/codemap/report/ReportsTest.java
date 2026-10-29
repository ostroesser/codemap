package codemap.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import codemap.ScannedFixture;
import codemap.db.Database;
import codemap.db.SchemaDoc;
import codemap.run.Runner;

class ReportsTest {

    static Connection c;

    @BeforeAll
    static void open() throws Exception {
        c = Database.openReadOnly(ScannedFixture.db());
    }

    @Test
    void briefFitsAndDocumentsEveryObjectWithAnExample() throws Exception {
        String brief = new Brief(c).render();
        assertTrue(brief.length() <= Brief.MAX_CHARS, "brief is " + brief.length());
        for (SchemaDoc.ObjectDoc o : SchemaDoc.load().objects()) {
            if (o.name().equals("schema_doc")) {
                continue; // internal: the brief is generated from it
            }
            if (o.name().endsWith("_effective") || o.name().endsWith("_compare")) {
                assertTrue(brief.contains(o.name()), o.name());
                continue;
            }
            assertTrue(brief.contains(o.name() + "("), "missing " + o.name());
            assertTrue(brief.contains("e.g. " + o.exampleQuery()), "example of " + o.name());
        }
        assertTrue(brief.contains("Facts established:"));
        assertTrue(brief.contains("app 'shop' with 3 repos, services catalog, orders."));
        assertFalse(brief.contains("runtime actuator data"));
    }

    @Test
    void briefMentionsRuntimeFactsWhenPresent(@TempDir Path tmp) throws Exception {
        Path db = copy(tmp);
        try (Connection w = Database.openForWrite(db); var st = w.createStatement()) {
            st.execute("INSERT INTO beans(service, bean_name, source) VALUES ('orders', 'x', 'runtime')");
        }
        try (Connection r = Database.openReadOnly(db)) {
            String brief = new Brief(r).render();
            assertTrue(brief.contains("plus runtime actuator data for services orders"));
            assertTrue(brief.contains("observed truth"));
            assertTrue(brief.length() <= Brief.MAX_CHARS);
        }
    }

    @Test
    void digestCoversTheApplication() throws Exception {
        String d = new Digest(c).render("shop", Digest.MAX_CHARS);
        for (String expected : new String[]{
                "3 repos, 5 modules, 2 services, 27 classes, 5 HTTP endpoints, 3 async entry points, 4 entities.",
                "- shop-common [library] 5 classes",
                "- orders-service -> shop-common (shop-common)",
                "- GET /products/{id} -> ProductController#get",
                "- kafka shop.orders.v1 [group catalog] -> OrderEventsListener#onOrderPlaced",
                "- scheduled (cron=0 0 * * * *) -> StaleOrderJob#purgeStaleOrders",
                "- shop.orders.v1 (kafka): orders:DefaultOrderService#place -> catalog:OrderEventsListener#onOrderPlaced",
                "- shop.orders.audit (kafka): orders:DefaultOrderService#place -> (no consumer found)",
                "- orders -> catalog: GET /products/{id} (CatalogClient#get => ProductController#get)",
                "- service: ProductService",
                "- repository: ProductRepository -> Product",
                "- Order [purchase_orders] (orders): lines one_to_many OrderLine",
                "profile_dependent 3",
                "Rel(svc_orders, svc_catalog, \"GET /products/{id}\", \"HTTP\")"}) {
            assertTrue(d.contains(expected), "missing: " + expected + "\n" + d);
        }
        assertThrows(IllegalArgumentException.class, () -> new Digest(c).render("nope", 1000));
    }

    @Test
    void digestDegradesLeastImportantSectionsFirst() throws Exception {
        String full = new Digest(c).render("shop", Digest.MAX_CHARS);
        String small = new Digest(c).render("shop", 2_500);
        assertTrue(small.length() <= 2_500, "length " + small.length());
        assertTrue(small.contains("(omitted for size: `diagram app`)"), small);
        assertTrue(small.contains("(summarised: names via"), small);
        assertTrue(small.contains("## Integration"), "integration is kept longest: " + small);
        assertTrue(full.length() > small.length());
    }

    @Test
    void digestOfALargeApplicationStaysWithinBudget(@TempDir Path tmp) throws Exception {
        Path db = copy(tmp);
        try (Connection w = Database.openForWrite(db)) {
            w.setAutoCommit(false);
            try (PreparedStatement cls = w.prepareStatement("INSERT INTO classes(fqn, simple_name, package, repo,"
                    + " module, kind, role, is_external) VALUES (?, ?, ?, 'orders', 'orders-service', 'class', ?, 0)");
                 PreparedStatement ep = w.prepareStatement("INSERT INTO endpoints(service, http_method, path, path_key,"
                         + " class_fqn, method_id, source) VALUES ('orders', 'GET', ?, ?, ?, ?, 'static')")) {
                String[] roles = {"service", "controller", "component", "client"};
                for (int i = 0; i < 4000; i++) {
                    String pkg = "com.example.shop.orders.generated.p" + (i % 40);
                    String name = "GeneratedComponentWithAVeryLongName" + i;
                    cls.setString(1, pkg + "." + name);
                    cls.setString(2, name);
                    cls.setString(3, pkg);
                    cls.setString(4, roles[i % roles.length]);
                    cls.addBatch();
                    ep.setString(1, "/api/generated/resource" + i + "/{id}");
                    ep.setString(2, "/api/generated/resource" + i + "/{}");
                    ep.setString(3, pkg + "." + name);
                    ep.setString(4, pkg + "." + name + "#handle(Long)");
                    ep.addBatch();
                }
                cls.executeBatch();
                ep.executeBatch();
            }
            w.commit();
        }
        try (Connection r = Database.openReadOnly(db)) {
            String d = new Digest(r).render("shop", Digest.MAX_CHARS);
            assertTrue(d.length() <= Digest.MAX_CHARS, "length " + d.length());
            assertTrue(d.contains("(summarised:"), "degraded");
            assertTrue(d.contains("- orders-service -> shop-common (shop-common)"), "small sections intact");
        }
    }

    @Test
    void diagramsForRepoComponentsAndArtifacts() throws Exception {
        Diagrams d = new Diagrams(c);
        String components = d.components("catalog");
        assertTrue(components.startsWith("@startuml\n!include <C4/C4_Component>"), components);
        assertTrue(components.contains("Component(cls_com_example_shop_catalog_app_ProductService, \"ProductService\", \"service\")"));
        assertTrue(components.contains("Rel(cls_com_example_shop_catalog_web_ProductController,"
                + " cls_com_example_shop_catalog_app_ProductService, \"uses\")"), components);
        assertTrue(components.contains("ComponentQueue_Ext(ext_shop_orders_v1"), components);
        assertTrue(components.trim().endsWith("@enduml"));

        String artifacts = d.artifacts("shop-common");
        assertTrue(artifacts.contains("Component(mod_shop_common, \"shop-common\", \"jar\")"), artifacts);
        assertTrue(artifacts.contains("Component_Ext(mod_orders_service, \"orders-service\", \"service\")"), artifacts);
        assertTrue(artifacts.contains("Rel(mod_catalog_service, mod_shop_common, \"compile\")"), artifacts);
    }

    @Test
    void diagramVerbInCmdBlocks() throws Exception {
        Runner runner = new Runner(ScannedFixture.db(), 80_000, Map.of("diagram", ReportCommands.DIAGRAM_VERB));
        String out = runner.run("```cmd\ndiagram orders artifacts\ndiagram app\ndiagram nope\n```", 1, null).text();
        assertTrue(out.contains("### C1 · diagram orders artifacts\n```plantuml\n@startuml"), out);
        assertTrue(out.contains("title shop overview"), out);
        assertTrue(out.contains("ERROR: unknown repo 'nope'"), out);
    }

    private static Path copy(Path tmp) throws Exception {
        Path db = tmp.resolve("copy.db");
        Files.copy(ScannedFixture.db(), db);
        return db;
    }
}
