package codemap.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class BlocksTest {

    @Test
    void extractsSqlAndCmdBlocksInOrder() {
        String answer = """
                Some reasoning with `inline code`.
                ```sql
                SELECT 1;
                -- a comment; with a semicolon
                SELECT 'a;b' AS x;
                ```
                ```java
                class Ignored {}
                ```
                ~~~cmd
                source Foo#bar
                # comment line
                diagram shop-common
                ~~~
                ```SQL
                SELECT 2
                ```
                """;
        List<Blocks.Request> r = Blocks.extract(answer);
        assertEquals(List.of(
                new Blocks.Request(Blocks.Kind.SQL, "SELECT 1"),
                new Blocks.Request(Blocks.Kind.SQL, "-- a comment; with a semicolon\nSELECT 'a;b' AS x"),
                new Blocks.Request(Blocks.Kind.CMD, "source Foo#bar"),
                new Blocks.Request(Blocks.Kind.CMD, "diagram shop-common"),
                new Blocks.Request(Blocks.Kind.SQL, "SELECT 2")), r);
    }

    @Test
    void bareSqlWithoutFencesIsAccepted() {
        assertEquals(2, Blocks.extract("select 1; select 2;").size());
        Blocks.Extraction prose = Blocks.extractAll("No blocks here, just prose.");
        assertEquals(0, prose.requests().size());
        assertEquals(1, prose.ignored());
    }

    @Test
    void copiedSqlBlockWithCommentsAndCommands() {
        String copied = """
                -- Q1: entry points per service
                SELECT service, kind, COUNT(*) AS n   -- count them
                FROM entry_points
                GROUP BY service, kind;

                /* Q2: who consumes the orders topic?
                   (multi-line comment; with a semicolon) */
                SELECT consumer_service
                FROM message_flows
                WHERE destination LIKE '%orders%';  -- trailing comment

                -- cmd: source DefaultOrderService#place
                -- cmd: diagram orders
                -- a final comment only
                """;
        Blocks.Extraction e = Blocks.extractAll(copied);
        assertEquals(0, e.ignored());
        assertEquals(List.of(Blocks.Kind.SQL, Blocks.Kind.SQL, Blocks.Kind.CMD, Blocks.Kind.CMD),
                e.requests().stream().map(Blocks.Request::kind).toList());
        assertEquals("-- Q1: entry points per service\nSELECT service, kind, COUNT(*) AS n   -- count them\n"
                + "FROM entry_points\nGROUP BY service, kind", e.requests().get(0).text());
        assertTrue(e.requests().get(1).text().endsWith("WHERE destination LIKE '%orders%'"));
        assertEquals("source DefaultOrderService#place", e.requests().get(2).text());
        assertEquals("diagram orders", e.requests().get(3).text());
    }

    @Test
    void commandsKeepTheirPositionAndBareCommandsWorkBetweenStatements() {
        List<Blocks.Request> r = Blocks.extract("""
                SELECT 1;
                source Foo#bar
                SELECT 2;
                diagram shop-common artifacts;
                """);
        assertEquals(List.of(
                new Blocks.Request(Blocks.Kind.SQL, "SELECT 1"),
                new Blocks.Request(Blocks.Kind.CMD, "source Foo#bar"),
                new Blocks.Request(Blocks.Kind.SQL, "SELECT 2"),
                new Blocks.Request(Blocks.Kind.CMD, "diagram shop-common artifacts")), r);
    }

    @Test
    void aColumnNamedSourceInsideAQueryIsNotACommand() {
        List<Blocks.Request> r = Blocks.extract("""
                SELECT DISTINCT
                  source FROM beans;
                """);
        assertEquals(List.of(new Blocks.Request(Blocks.Kind.SQL, "SELECT DISTINCT\n  source FROM beans")), r);
    }

    @Test
    void proseAroundUnfencedSqlIsSkipped() {
        Blocks.Extraction e = Blocks.extractAll("""
                Let me check the entry points first.
                SELECT service FROM services;
                Then I will look at the flows.
                """);
        assertEquals(List.of(new Blocks.Request(Blocks.Kind.SQL, "SELECT service FROM services")), e.requests());
        assertEquals(1, e.ignored());
    }

    @Test
    void commentsAreSeenThroughWhenCheckingForAQuery() {
        assertTrue(Blocks.isQuery("-- note\n/* block */ WITH x AS (SELECT 1) SELECT * FROM x"));
        assertTrue(Blocks.isQuery("select(1)"));
        assertFalse(Blocks.isQuery("-- note\nDELETE FROM classes"));
        assertEquals("SELECT 1", Blocks.stripLeadingComments("  -- a\n  /* b */\nSELECT 1"));
    }

    @Test
    void sqlBlocksInFencedAnswersAcceptCmdComments() {
        assertEquals(List.of(new Blocks.Request(Blocks.Kind.SQL, "SELECT 1"),
                        new Blocks.Request(Blocks.Kind.CMD, "source Foo")),
                Blocks.extract("Text\n```sql\nSELECT 1;\n-- cmd: source Foo\n```\nFacts established: ..."));
    }

    @Test
    void unterminatedFenceTakesTheRest() {
        assertEquals(List.of(new Blocks.Request(Blocks.Kind.SQL, "SELECT 3")), Blocks.extract("```sql\nSELECT 3\n"));
    }

    @Test
    void splitKeepsQuotedSemicolonsAndBlockComments() {
        assertEquals(List.of("SELECT \"a;\" /* ; */ FROM t", "SELECT 'it''s; ok'"),
                Blocks.splitStatements("SELECT \"a;\" /* ; */ FROM t; SELECT 'it''s; ok'; -- trailing\n"));
    }
}
