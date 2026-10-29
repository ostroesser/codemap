package codemap.scan.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PlaceholdersTest {

    private final Placeholders p = Placeholders.fromRows(List.of(
            new String[]{"(default)", "a.topic", "orders.${env}"},
            new String[]{"(default)", "env", "prod"},
            new String[]{"(default)", "a.topic", "ignored: first row wins"},
            new String[]{"(default)", "server.base-url", "http://h:1"},
            new String[]{"dev", "env", "dev"}));

    @Test
    void resolvesNestedDefaultsAndRelaxedNames() {
        assertEquals("orders.prod", p.resolve("${a.topic}").value());
        assertEquals("x-fallback", p.resolve("x-${missing:fallback}").value());
        assertEquals("http://h:1/p", p.resolve("${server.baseUrl}/p").value());
        assertEquals("prod", p.resolve("${missing:${env}}").value());
        assertEquals("a:b", p.resolve("${missing:a:b}").value());
    }

    @Test
    void reportsUnresolvedAndProfileDependentKeys() {
        Placeholders.Result r = p.resolve("${nope}/${a.topic}");
        assertEquals("${nope}/orders.prod", r.value());
        assertEquals(Set.of("nope"), r.unresolved());
        assertTrue(r.profileDependent().contains("env"));
        assertTrue(p.resolve("plain").complete());
    }
}
