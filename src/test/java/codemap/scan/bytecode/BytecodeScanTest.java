package codemap.scan.bytecode;

import static codemap.TestDb.one;
import static codemap.TestDb.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import codemap.ScannedFixture;
import codemap.db.Database;

class BytecodeScanTest {

    static Connection c;

    @BeforeAll
    static void open() throws Exception {
        c = Database.openReadOnly(ScannedFixture.db());
    }

    private static final String P = "com.example.shop.";

    @Test
    void classesWithKindsAndSourceLocations() throws Exception {
        assertEquals("27", one(c, "SELECT COUNT(*) FROM classes WHERE is_external = 0"));
        assertEquals("interface|0", one(c, "SELECT kind, is_abstract FROM classes WHERE simple_name = 'OrderService'"));
        assertEquals("record", one(c, "SELECT kind FROM classes WHERE simple_name = 'ProductDto'"));
        assertEquals("annotation", one(c, "SELECT kind FROM classes WHERE simple_name = 'UseCase'"));
        assertEquals("orders-service/src/main/java/com/example/shop/orders/web/OrderController.java|orders|orders-service",
                one(c, "SELECT source_file, repo, module FROM classes WHERE simple_name = 'OrderController'"));
        assertEquals("src/main/java/com/example/shop/catalog/app/ProductService.java",
                one(c, "SELECT source_file FROM classes WHERE simple_name = 'ProductService'"));
        assertEquals("1", one(c, "SELECT COUNT(*) FROM classes WHERE is_external = 1"
                + " AND fqn = 'org.springframework.data.repository.Repository'"));
        assertEquals(List.of("catalog|10", "orders|12", "shop-common|5"),
                rows(c, "SELECT repo, class_count FROM repos ORDER BY repo"));
    }

    @Test
    void readableMethodSignaturesAndLines() throws Exception {
        assertEquals("Long place(long productId, int quantity)|33|41", one(c,
                "SELECT signature, first_line, last_line FROM methods WHERE method_id = '"
                        + P + "orders.app.DefaultOrderService#place(long,int)'"));
        assertEquals("<init>(OrderRepository orders, CatalogClient catalog, KafkaTemplate<String, Object> kafka,"
                + " String ordersTopic)", one(c, "SELECT signature FROM methods WHERE class_fqn = '"
                + P + "orders.app.DefaultOrderService' AND name = '<init>'"));
        assertEquals("List<ProductDto> list(String category)", one(c, "SELECT signature FROM methods"
                + " WHERE method_id = '" + P + "catalog.web.ProductController#list(String)'"));
        assertEquals("1|", one(c, "SELECT is_abstract, COALESCE(first_line, '') FROM methods"
                + " WHERE method_id = '" + P + "common.catalog.CatalogClient#get(long)'"));
        assertEquals("0", one(c, "SELECT COUNT(*) FROM methods WHERE name LIKE 'lambda$%'"));
        assertEquals("List<OrderLine>", one(c, "SELECT type FROM fields WHERE class_fqn = '"
                + P + "orders.domain.Order' AND name = 'lines'"));
    }

    @Test
    void annotationsWithAttributes() throws Exception {
        assertEquals("{\"topics\":[\"${shop.topics.orders}\"],\"groupId\":\"catalog\"}", one(c,
                "SELECT attributes FROM annotations WHERE annotation = 'KafkaListener'"));
        assertEquals("{\"value\":\"id\"}", one(c, "SELECT attributes FROM annotations WHERE target = '"
                + P + "orders.web.OrderController#get(Long)/0'"));
        assertEquals("{\"consumes\":[\"application/json\"]}", one(c,
                "SELECT attributes FROM annotations WHERE annotation = 'PostMapping' AND class_fqn LIKE '%OrderController'"));
        assertEquals("{\"name\":\"purchase_orders\"}", one(c, "SELECT attributes FROM annotations"
                + " WHERE target = '" + P + "orders.domain.Order' AND annotation = 'Table'"));
        assertEquals("field", one(c, "SELECT target_kind FROM annotations WHERE annotation = 'OneToMany'"));
    }

    @Test
    void metaAnnotationsAreTransitive() throws Exception {
        assertEquals(List.of("org.springframework.stereotype.Component|2", "org.springframework.stereotype.Service|1"),
                rows(c, "SELECT meta_annotation_fqn, depth FROM annotation_meta WHERE annotation_fqn = '"
                        + P + "common.stereotype.UseCase' AND meta_annotation_fqn LIKE '%.stereotype.%'"
                        + " AND meta_annotation_fqn <> 'org.springframework.stereotype.Indexed' ORDER BY 1"));
        assertEquals("1", one(c, "SELECT COUNT(*) FROM annotation_meta WHERE annotation_fqn ="
                + " 'org.springframework.web.bind.annotation.GetMapping'"
                + " AND meta_annotation_fqn = 'org.springframework.web.bind.annotation.RequestMapping'"));
    }

    @Test
    void hierarchyIsTransitiveThroughLibraryTypes() throws Exception {
        assertEquals("implements|4", one(c, "SELECT relation, depth FROM type_hierarchy WHERE class_fqn = '"
                + P + "catalog.domain.ProductRepository' AND supertype_fqn = 'org.springframework.data.repository.Repository'"));
        assertEquals(List.of(P + "catalog.app.JpaStockReservations", P + "catalog.app.LoggingStockReservations"),
                rows(c, "SELECT class_fqn FROM type_hierarchy WHERE supertype_fqn = '"
                        + P + "catalog.app.StockReservations' ORDER BY 1"));
        assertEquals("extends|1", one(c, "SELECT relation, depth FROM type_hierarchy WHERE class_fqn = '"
                + P + "common.events.OrderPlacedEvent' AND supertype_fqn = 'java.lang.Record'"));
    }

    @Test
    void rolesFollowStereotypesAndMetaAnnotations() throws Exception {
        assertEquals(List.of(
                        "CatalogApplication|application|@SpringBootApplication",
                        "CatalogClient|client|@HttpExchange",
                        "Category|entity|@Entity",
                        "ClientConfig|configuration|@Configuration",
                        "DefaultOrderService|service|@Service",
                        "JpaStockReservations|component|@Component",
                        "LoggingStockReservations|component|@Component",
                        "Order|entity|@Entity",
                        "OrderController|controller|@RestController",
                        "OrderEventsListener|listener|@KafkaListener on a method",
                        "OrderLine|entity|@Entity",
                        "OrderPlacedEvent|null|null",
                        "OrderRepository|repository|extends Repository",
                        "OrderService|null|null",
                        "OrdersApplication|application|@SpringBootApplication",
                        "PlaceOrderRequest|null|null",
                        "Product|entity|@Entity",
                        "ProductController|controller|@RestController",
                        "ProductDto|null|null",
                        "ProductRepository|repository|extends Repository",
                        "ProductService|service|@Service via @UseCase",
                        "StaleOrderJob|listener|@Scheduled on a method",
                        "StartupLogger|listener|@EventListener on a method",
                        "StockAuditClient|component|@Component",
                        "StockReservations|null|null",
                        "Topics|null|null",
                        "UseCase|null|null"),
                rows(c, "SELECT simple_name, role, role_reason FROM classes WHERE is_external = 0 ORDER BY simple_name"));
    }

    @Test
    void callEdgesKeepInternalAndIntegrationTargets() throws Exception {
        assertEquals(List.of(
                        P + "common.catalog.CatalogClient#get(long)|33|interface",
                        P + "common.catalog.CatalogClient#reserve(long,int)|34|interface",
                        P + "orders.domain.OrderRepository#save(Object)|38|interface",
                        "org.springframework.kafka.core.KafkaTemplate#send(String,Object)|39|virtual",
                        "org.springframework.kafka.core.KafkaTemplate#send(String,Object)|40|virtual"),
                rows(c, "SELECT callee_method, line, invoke_kind FROM calls WHERE caller_method = '"
                        + P + "orders.app.DefaultOrderService#place(long,int)'"
                        + " AND callee_class NOT LIKE '%.Order' AND callee_class NOT LIKE '%Event'"
                        + " AND callee_class NOT LIKE '%Dto' ORDER BY line, callee_method"));
        assertEquals("0", one(c, "SELECT COUNT(*) FROM calls WHERE callee_class LIKE 'java.%'"));
    }

    @Test
    void lambdaBodiesAndMethodReferencesBelongToTheEnclosingMethod() throws Exception {
        assertEquals("1", one(c, "SELECT COUNT(*) FROM calls WHERE caller_method = '"
                + P + "orders.app.StaleOrderJob#purgeStaleOrders()' AND callee_method LIKE '%OrderRepository#delete(%'"));
        assertEquals("dynamic", one(c, "SELECT invoke_kind FROM calls WHERE caller_method = '"
                + P + "catalog.app.ProductService#list(String)' AND callee_method LIKE '%#toDto(Product)'"));
    }

    @Test
    void callsResolvedExpandsInterfacesButKeepsProxies() throws Exception {
        assertEquals(List.of(P + "catalog.app.JpaStockReservations#reserve(long,int)|1",
                        P + "catalog.app.LoggingStockReservations#reserve(long,int)|1"),
                rows(c, "SELECT target_method, via_interface FROM calls_resolved WHERE caller_method = '"
                        + P + "catalog.app.ProductService#reserve(long,int)' ORDER BY 1"));
        assertEquals(P + "orders.app.DefaultOrderService#place(long,int)", one(c,
                "SELECT target_method FROM calls_resolved WHERE callee_method = '" + P + "orders.app.OrderService#place(long,int)'"));
        assertEquals(P + "common.catalog.CatalogClient#get(long)|0", one(c,
                "SELECT target_method, via_interface FROM calls_resolved WHERE callee_method = '"
                        + P + "common.catalog.CatalogClient#get(long)'"));
    }

    @Test
    void classDependenciesAreWeighted() throws Exception {
        assertTrue(Integer.parseInt(one(c, "SELECT weight FROM class_dependencies WHERE from_class = '"
                + P + "orders.app.DefaultOrderService' AND to_class = '" + P + "orders.domain.Order'")) > 3);
        assertEquals("0", one(c, "SELECT COUNT(*) FROM class_dependencies WHERE to_class LIKE 'org.%'"));
    }

    @Test
    void classSummaryCounts() throws Exception {
        assertEquals("interface|2|2|0", one(c, "SELECT kind, methods, calls_in, calls_out FROM class_summary"
                + " WHERE simple_name = 'OrderService'"));
    }
}
