package codemap.scan.bytecode;

import static codemap.TestDb.one;
import static codemap.TestDb.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import codemap.ScannedFixture;
import codemap.db.Database;
import codemap.fixture.Fixture;
import codemap.scan.LocalConfig;
import codemap.scan.Scanner;

class RolesConfigTest {

    @Test
    void localRulesWinOverBuiltIns(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("codemap.local.yml");
        Files.writeString(config, """
                roles:
                  - annotation: com.example.shop.common.stereotype.UseCase
                    role: use_case
                  - extends: com.example.shop.catalog.app.StockReservations
                    role: adapter
                  - package: "*.messaging.*"
                    role: inbound
                calls:
                  include: [java.util.List]
                """);
        Path db = tmp.resolve("x.db");
        Scanner.scan(db, List.of(Fixture.shared()), ScannedFixture.quiet(LocalConfig.load(config)));
        try (Connection c = Database.openReadOnly(db)) {
            assertEquals(List.of(
                            "JpaStockReservations|adapter|local rule: extends StockReservations",
                            "LoggingStockReservations|adapter|local rule: extends StockReservations",
                            "OrderEventsListener|inbound|local rule: package *.messaging.*",
                            "ProductService|use_case|local rule: @UseCase"),
                    rows(c, "SELECT simple_name, role, role_reason FROM classes WHERE role_reason LIKE 'local%'"
                            + " ORDER BY simple_name"));
            assertEquals("1", one(c, "SELECT COUNT(*) > 0 FROM calls WHERE callee_class = 'java.util.List'"));
        }
    }

    @Test
    void rejectsAmbiguousRules(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("c.yml");
        Files.writeString(config, "roles:\n  - annotation: a.B\n    package: x\n    role: r\n");
        assertThrows(IOException.class, () -> LocalConfig.load(config));
        Files.writeString(config, "roles:\n  - annotation: a.B\n");
        assertThrows(IOException.class, () -> LocalConfig.load(config));
    }

    @Test
    void withoutDependencyJarsBuiltInSpringKnowledgeStillAssignsRoles(@TempDir Path tmp) throws Exception {
        Path ws = tmp.resolve("ws");
        Fixture.copy(ws);
        try (Stream<Path> s = Files.walk(ws)) {
            for (Path p : s.filter(f -> f.getFileName().toString().equals("codemap.classpath")).toList()) {
                Files.delete(p);
            }
        }
        Path db = tmp.resolve("x.db");
        Scanner.scan(db, List.of(ws), ScannedFixture.quiet(LocalConfig.defaults()));
        try (Connection c = Database.openReadOnly(db)) {
            assertEquals(List.of(
                            "OrderController|controller|@RestController",
                            "ProductRepository|repository|extends Spring Data repository",
                            "ProductService|service|@Service via @UseCase"),
                    rows(c, "SELECT simple_name, role, role_reason FROM classes"
                            + " WHERE simple_name IN ('OrderController', 'ProductRepository', 'ProductService')"
                            + " ORDER BY simple_name"));
            assertEquals("no_classpath", one(c, "SELECT kind FROM scan_warnings WHERE repo IS NULL"));
        }
    }

    @Test
    void duplicateClassesAcrossModulesAreReportedOnce(@TempDir Path tmp) throws Exception {
        Path ws = tmp.resolve("ws");
        Fixture.copy(ws);
        Path copy = ws.resolve("catalog/target/classes/com/example/shop/common/events");
        Files.createDirectories(copy);
        Files.copy(ws.resolve("shop-common/target/classes/com/example/shop/common/events/Topics.class"),
                copy.resolve("Topics.class"));
        Path db = tmp.resolve("x.db");
        Scanner.scan(db, List.of(ws), ScannedFixture.quiet(LocalConfig.defaults()));
        try (Connection c = Database.openReadOnly(db)) {
            // Repos are scanned in name order, so the copy in catalog is kept.
            assertEquals("catalog-service", one(c, "SELECT module FROM classes WHERE simple_name = 'Topics'"));
            assertEquals("shop-common|duplicate_class", one(c, "SELECT repo, kind FROM scan_warnings WHERE kind = 'duplicate_class'"));
        }
    }
}
