package codemap.scan.spring;

import static codemap.TestDb.one;
import static codemap.TestDb.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import codemap.ScannedFixture;
import codemap.db.Database;

class SpringFactsTest {

    static Connection c;
    static final String P = "com.example.shop.";

    @BeforeAll
    static void open() throws Exception {
        c = Database.openReadOnly(ScannedFixture.db());
    }

    @Test
    void servicesAndTheirModules() throws Exception {
        assertEquals(List.of(
                        "catalog|catalog-service|" + P + "catalog.CatalogApplication|com.example.shop.catalog",
                        "orders|orders-service|" + P + "orders.OrdersApplication|com.example.shop.orders,com.example.shop.common"),
                rows(c, "SELECT service, module, main_class, scan_packages FROM services ORDER BY service"));
        assertEquals(List.of("orders-domain|1", "orders-service|0", "shop-common|1"),
                rows(c, "SELECT module, depth FROM service_modules WHERE service = 'orders' ORDER BY module"));
        assertEquals(List.of("catalog-service", "orders-service"),
                rows(c, "SELECT module FROM modules WHERE is_service = 1 ORDER BY module"));
    }

    @Test
    void beansFromScanBeanMethodsAndRepositories() throws Exception {
        assertEquals(List.of(
                        "catalogClient|" + P + "common.catalog.CatalogClient|bean_method|" + P
                                + "orders.config.ClientConfig#catalogClient(String)",
                        "orderRepository|" + P + "orders.domain.OrderRepository|data_repository|null"),
                rows(c, "SELECT bean_name, class_fqn, declared_by, factory_method FROM beans"
                        + " WHERE service = 'orders' AND declared_by <> 'scan' ORDER BY bean_name"));
        assertEquals("service|static", one(c, "SELECT role, source FROM beans WHERE bean_name = 'productService'"));
        assertEquals("profile=dev|0", one(c, "SELECT conditions, is_primary FROM beans WHERE bean_name = 'loggingStockReservations'"));
        // shop-common is outside catalog's scan package: its client interface is no bean there.
        assertEquals("0", one(c, "SELECT COUNT(*) FROM beans WHERE service = 'catalog' AND class_fqn LIKE '%CatalogClient'"));
    }

    @Test
    void injectionsResolveThroughInterfacesPrimaryAndProfiles() throws Exception {
        assertEquals(P + "orders.app.DefaultOrderService|1", one(c, "SELECT resolved_class, candidates FROM injections"
                + " WHERE consumer_class = '" + P + "orders.web.OrderController'"));
        assertEquals(P + "catalog.app.JpaStockReservations|2", one(c, "SELECT resolved_class, candidates FROM injections"
                + " WHERE consumer_class = '" + P + "catalog.app.ProductService' AND member = 'reservations'"));
        assertEquals(P + "common.catalog.CatalogClient", one(c, "SELECT resolved_class FROM injections"
                + " WHERE consumer_class = '" + P + "orders.app.DefaultOrderService' AND member = 'catalog'"));
        assertEquals("null|0", one(c, "SELECT resolved_class, candidates FROM injections WHERE member = 'kafka'"));
        assertEquals(List.of(P + "orders.app.DefaultOrderService|ordersTopic|${shop.topics.orders}",
                        P + "orders.config.ClientConfig|baseUrl|${shop.catalog.base-url}"),
                rows(c, "SELECT consumer_class, member, value_expr FROM injection_points"
                        + " WHERE value_expr IS NOT NULL ORDER BY 1"));
    }

    @Test
    void endpointsComposeClassAndMethodMappings() throws Exception {
        assertEquals(List.of(
                        "catalog|GET|/products|/products",
                        "catalog|GET|/products/{id}|/products/{}",
                        "catalog|POST|/products/{id}/reservations|/products/{}/reservations",
                        "orders|POST|/api/orders|/api/orders",
                        "orders|GET|/api/orders/{id}|/api/orders/{}"),
                rows(c, "SELECT service, http_method, path, path_key FROM endpoints ORDER BY service, path, http_method"));
        assertEquals("application/json", one(c, "SELECT consumes FROM endpoints WHERE service = 'orders' AND http_method = 'POST'"));
        assertEquals("5", one(c, "SELECT COUNT(*) FROM entry_points WHERE kind = 'http'"));
    }

    @Test
    void listenersWithResolvedDestinationsAndSchedules() throws Exception {
        assertEquals(List.of(
                        "catalog|kafka|shop.orders.v1|${shop.topics.orders}|catalog|OrderPlacedEvent|null",
                        "orders|event|null|null|null|ApplicationReadyEvent|null",
                        "orders|scheduled|null|null|null|null|cron=0 0 * * * *"),
                rows(c, "SELECT service, kind, destination, destination_expr, group_id, payload_type, schedule"
                        + " FROM listeners ORDER BY service, kind"));
    }

    @Test
    void outboundCallsWithTopicsFromValueAndConstants() throws Exception {
        assertEquals(List.of(
                        "kafka|39|shop.orders.v1|${shop.topics.orders}",
                        "kafka|40|shop.orders.audit|shop.orders.audit"),
                rows(c, "SELECT kind, line, target, target_expr FROM outbound WHERE kind = 'kafka' ORDER BY line"));
        assertEquals(List.of("GET|/products/{id}|/products/{}", "POST|/products/{id}/reservations|/products/{}/reservations"),
                rows(c, "SELECT http_method, target, path_key FROM outbound WHERE kind = 'http_exchange' ORDER BY target"));
    }

    @Test
    void messageAndHttpFlowsLinkServices() throws Exception {
        assertEquals(List.of("shop.orders.audit|orders|null", "shop.orders.v1|orders|catalog"),
                rows(c, "SELECT destination, producer_service, consumer_service FROM message_flows ORDER BY destination"));
        assertEquals(List.of(
                        "orders|GET|catalog|" + P + "catalog.web.ProductController#get(long)|path+method",
                        "orders|POST|catalog|" + P + "catalog.web.ProductController#reserve(long,int)|path+method"),
                rows(c, "SELECT client_service, http_method, server_service, server_method, match_quality"
                        + " FROM http_flows ORDER BY http_method"));
    }

    @Test
    void jpaEntitiesRelationsAndRepositories() throws Exception {
        assertEquals(List.of("Category|categories", "Order|purchase_orders", "OrderLine|order_line", "Product|product"),
                rows(c, "SELECT c.simple_name, e.table_name FROM entities e JOIN classes c ON c.fqn = e.class_fqn"
                        + " ORDER BY 1"));
        assertEquals(P + "orders.domain.OrderLine|order", one(c, "SELECT target_entity, mapped_by FROM entity_relations"
                + " WHERE relation = 'one_to_many'"));
        assertEquals(P + "catalog.domain.Product|Long|JpaRepository", one(c, "SELECT entity_fqn, id_type, base_interface"
                + " FROM data_repositories WHERE class_fqn LIKE '%ProductRepository'"));
    }

    @Test
    void profileDependentValuesAreFlagged() throws Exception {
        assertEquals(List.of("catalog|catalog-service", "orders|orders-service", "orders|orders-service"),
                rows(c, "SELECT repo, module FROM scan_warnings WHERE kind = 'profile_dependent' ORDER BY 1, message"));
    }
}
