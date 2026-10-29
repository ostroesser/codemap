package codemap.scan.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFilesTest {

    @Test
    void yamlKeepsRawScalarsAndFlattensListsAndDottedKeys() {
        var docs = ConfigFiles.parseYaml("""
                a:
                  port: 08080
                  on: on
                  day: 2024-01-01
                  empty:
                  list: [x, y]
                  servers:
                    - host: h1
                      port: 1
                  "[weird.key]": v
                logging.level.com.example: DEBUG
                """);
        assertEquals(1, docs.size());
        Map<String, String> d = docs.get(0);
        assertEquals("08080", d.get("a.port"));
        assertEquals("on", d.get("a.on"));
        assertEquals("2024-01-01", d.get("a.day"));
        assertEquals("", d.get("a.empty"));
        assertEquals("y", d.get("a.list[1]"));
        assertEquals("h1", d.get("a.servers[0].host"));
        assertEquals("v", d.get("a.[weird.key]"));
        assertEquals("DEBUG", d.get("logging.level.com.example"));
    }

    @Test
    void propertiesFilesAndMultiDocumentProfiles(@TempDir Path dir) throws Exception {
        Path res = Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.properties"), """
                server.port=8080
                #---
                spring.config.activate.on-profile=prod
                server.port=80
                """);
        Files.writeString(res.resolve("application-test.yaml"), "x: 1\n");
        Files.writeString(res.resolve("bootstrap.yml"), "spring.cloud.config.enabled: false\n---\nspring.profiles: legacy\ny: 2\n");
        Files.writeString(res.resolve("logback.xml"), "<configuration/>");

        var result = ConfigFiles.scanModule(dir);
        assertEquals(List.of(), result.problems());
        List<String> rows = result.entries().stream()
                .map(e -> e.file() + "|" + e.profile() + "|" + e.key() + "=" + e.value()).toList();
        assertEquals(List.of(
                "application-test.yaml|test|x=1",
                "application.properties|(default)|server.port=8080",
                "application.properties|prod|server.port=80",
                "application.properties|prod|spring.config.activate.on-profile=prod",
                "bootstrap.yml|(default)|spring.cloud.config.enabled=false",
                "bootstrap.yml|legacy|spring.profiles=legacy",
                "bootstrap.yml|legacy|y=2"), rows);
    }

    @Test
    void brokenYamlIsReportedNotFatal(@TempDir Path dir) throws Exception {
        Path res = Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.yml"), "a: [unclosed\n");
        var result = ConfigFiles.scanModule(dir);
        assertEquals(1, result.problems().size());
        assertEquals(List.of(), result.entries());
    }
}
