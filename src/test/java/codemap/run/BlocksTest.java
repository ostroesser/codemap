package codemap.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(0, Blocks.extract("No blocks here, just prose.").size());
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
