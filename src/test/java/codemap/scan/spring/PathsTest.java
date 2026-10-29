package codemap.scan.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PathsTest {

    @Test
    void joinsSegments() {
        assertEquals("/api/orders/{id}", Paths.join("/api/", "orders", "/{id}"));
        assertEquals("/", Paths.join("", ""));
        assertEquals("/x", Paths.join("", "x/"));
        assertEquals("http://h:8080/a/b", Paths.join("http://h:8080/", "/a", "b"));
        assertEquals("${base}/a", Paths.join("${base}", "a"));
    }

    @Test
    void keysIgnoreHostQueryAndVariableNames() {
        assertEquals("/products/{}", Paths.key("http://catalog:8080/products/{productId}?x=1"));
        assertEquals("/products/{}/reservations", Paths.key("/products/{id}/reservations/"));
        assertEquals("/a", Paths.key("${catalog.url}/a"));
        assertEquals("/", Paths.key(""));
    }

    @Test
    void snakeCaseTableNames() {
        assertEquals("order_line", SpringScanner.snakeCase("OrderLine"));
        assertEquals("product", SpringScanner.snakeCase("Product"));
        assertEquals("urlmapping", SpringScanner.snakeCase("URLMapping"));
        assertEquals("orderid", SpringScanner.snakeCase("OrderID"));
        assertEquals("purchase_order_line", SpringScanner.snakeCase("PurchaseOrderLine"));
    }
}
