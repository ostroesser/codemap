package codemap.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import codemap.ScannedFixture;

class RunnerTest {

    private Runner.Result run(String input, int maxChars) throws Exception {
        return new Runner(ScannedFixture.db(), maxChars, Map.of()).run(input, 1, null);
    }

    @Test
    void formatsQueriesAsMarkdownTables() throws Exception {
        Runner.Result r = run("```sql\nSELECT service, kind FROM entry_points WHERE kind = 'kafka';\n```", 80_000);
        assertEquals("""
                ## codemap results · 1 query
                ### Q1 · 1 row · `SELECT service, kind FROM entry_points WHERE kind = 'kafka'`
                | service | kind |
                |---|---|
                | catalog | kafka |

                """, r.text());
    }

    @Test
    void nullsEmptyResultsAndCellEscaping() throws Exception {
        Runner.Result r = run("```sql\nSELECT NULL AS a, 'x|y' AS b, 'line1\nline2' AS c;\n"
                + "SELECT * FROM services WHERE 0;\n```", 80_000);
        assertTrue(r.text().contains("|  | x\\|y | line1 ⏎ line2 |"), r.text());
        assertTrue(r.text().contains("### Q2 · 0 rows"), r.text());
        assertTrue(r.text().contains("(no rows)"), r.text());
    }

    @Test
    void errorsComeWithColumnHintsAndWritesAreRefused() throws Exception {
        Runner.Result r = run("```sql\nSELECT nope FROM listeners;\nSELECT * FROM listner;\n"
                + "DELETE FROM classes;\nCREATE TABLE x(y);\n```", 80_000);
        assertTrue(r.text().contains("ERROR: no such column: nope"), r.text());
        assertTrue(r.text().contains("hint: columns of listeners: service, kind, destination"), r.text());
        assertTrue(r.text().contains("ERROR: no such table: listner"), r.text());
        assertTrue(r.text().contains("hint: tables/views are listeners"), r.text());
        assertEquals(2, r.text().split("only read queries run here", -1).length - 1, r.text());
    }

    @Test
    void readOnlyEvenThroughCte() throws Exception {
        Runner.Result r = run("```sql\nWITH x AS (SELECT 1) DELETE FROM classes;\nSELECT COUNT(*) FROM classes;\n```", 80_000);
        assertTrue(r.text().contains("ERROR:"), r.text());
        assertFalse(r.text().contains("| 0 |"), r.text());
    }

    @Test
    void budgetTruncatesWithExplicitNoteAndPagingContinues() throws Exception {
        String input = "```sql\nSELECT method_id, signature FROM methods ORDER BY method_id;\n"
                + "SELECT COUNT(*) AS n FROM classes;\n```";
        Runner.Result r = run(input, 3_000);
        assertTrue(r.text().length() <= 3_000, "length " + r.text().length());
        assertTrue(r.text().matches("(?s).*TRUNCATED: \\d+ more rows in Q1, refine the query \\(filter, aggregate\\)"
                + " or use `codemap run --page 2`.*"), r.text());
        assertTrue(r.text().contains("| n |"), "small query kept whole: " + r.text());
        int shown = r.unitsShown().get(0);
        assertTrue(shown > 5);

        Runner.Result page2 = new Runner(ScannedFixture.db(), 3_000, Map.of()).run(input, 2, r.unitsShown());
        assertTrue(page2.text().contains("### Q1 · rows " + (shown + 1) + "+ of"), page2.text());
        String firstRowPage2 = page2.text().lines().filter(l -> l.startsWith("| com.")).findFirst().orElseThrow();
        assertFalse(r.text().contains(firstRowPage2), "page 2 starts after page 1");
        assertTrue(page2.text().contains("(no more rows)"), "Q2 had one row: " + page2.text());
    }

    @Test
    void longCellsAreCut() throws Exception {
        Runner.Result r = run("```sql\nSELECT printf('%.500c', 'x') AS long;\n```", 80_000);
        String row = r.text().lines().filter(l -> l.startsWith("| xxx")).findFirst().orElseThrow();
        assertTrue(row.length() < 210 && row.contains("…"), row);
    }

    @Test
    void sourceCommandsAndUnknownVerbs() throws Exception {
        Runner.Result r = run("```cmd\nsource ProductController#get\nsource OrderService\nsource Order#addLine(long,int,long)\n"
                + "source DefaultOrderService#DefaultOrderService\nfrobnicate x\n```", 80_000);
        String t = r.text();
        assertTrue(t.contains("catalog/src/main/java/com/example/shop/catalog/web/ProductController.java:30-33"), t);
        assertTrue(t.contains("    @GetMapping(\"/{id}\")\n    public ProductDto get(@PathVariable(\"id\") long id) {\n"
                + "        return products.get(id);\n    }"), t);
        assertTrue(t.contains("public interface OrderService {"), "whole class: " + t);
        assertTrue(t.contains("public void addLine(long productId, int quantity, long priceCents) {"), t);
        assertTrue(t.contains("public DefaultOrderService(OrderRepository orders"), "constructor by class name: " + t);
        assertTrue(t.contains("ERROR: unknown command; available: source"), t);
        assertEquals(5, r.commands());
    }

    @Test
    void noBlocksIsReported() throws Exception {
        Runner.Result r = run("just prose", 80_000);
        assertEquals(0, r.queries() + r.commands());
        assertTrue(r.text().contains("no SQL query or command found"));
    }

    @Test
    void copiedSqlWithCommentsRunsEndToEnd() throws Exception {
        Runner.Result r = run("""
                -- which services exist?
                SELECT service FROM services ORDER BY service;  -- alphabetical
                /* and the topic consumers */
                SELECT consumer_service FROM message_flows WHERE destination = 'shop.orders.v1';
                -- cmd: source OrderEventsListener#onOrderPlaced
                """, 80_000);
        String t = r.text();
        assertEquals(2, r.queries());
        assertEquals(1, r.commands());
        assertTrue(t.contains("### Q1 · 2 rows · `SELECT service FROM services ORDER BY service`"), t);
        assertTrue(t.contains("| catalog |\n| orders |"), t);
        assertTrue(t.contains("### Q2 · 1 row · `SELECT consumer_service FROM message_flows"), t);
        assertTrue(t.contains("public void onOrderPlaced(OrderPlacedEvent event) {"), t);
        assertFalse(t.contains("ERROR"), t);
    }

    @Test
    void commentedWriteStatementsAreStillRefused() throws Exception {
        Runner.Result r = run("-- harmless?\nDELETE FROM classes;\nSELECT COUNT(*) AS n FROM classes;", 80_000);
        assertTrue(r.text().contains("only read queries run here"), r.text());
        assertTrue(r.text().contains("| n |"), r.text());
    }

    @Test
    void manyQueriesShareTheBudget() throws Exception {
        StringBuilder sb = new StringBuilder("```sql\n");
        for (int i = 0; i < 8; i++) {
            sb.append("SELECT fqn, simple_name, package, module FROM classes;\n");
        }
        Runner.Result r = run(sb.append("```").toString(), 6_000);
        assertTrue(r.text().length() <= 6_000);
        assertEquals(8, r.text().split("### Q", -1).length - 1);
        List<Integer> shown = r.unitsShown();
        assertTrue(shown.stream().allMatch(n -> n > 0), shown.toString());
    }
}
