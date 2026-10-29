package codemap.scan.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

class PomReaderTest {

    @Test
    void interpolatesNestedAndLeavesUnknownPlaceholders() {
        Map<String, String> props = Map.of("a", "${b}-x", "b", "1.0", "project.version", "2.0");
        assertEquals("1.0-x", PomReader.interpolate("${a}", props));
        assertEquals("2.0/${unknown}", PomReader.interpolate("${project.version}/${unknown}", props));
        assertEquals("plain", PomReader.interpolate("plain", props));
    }
}
