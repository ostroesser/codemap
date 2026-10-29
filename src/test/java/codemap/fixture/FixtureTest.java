package codemap.fixture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class FixtureTest {

    @Test
    void everyModuleCompilesIntoTargetClasses() {
        Path root = Fixture.shared();
        for (Fixture.Module module : Fixture.MODULES) {
            Path classes = root.resolve(module.path()).resolve("target/classes");
            assertTrue(Files.isDirectory(classes), "missing " + classes);
        }
        assertTrue(Files.exists(root.resolve(
                "catalog/target/classes/com/example/shop/catalog/messaging/OrderEventsListener.class")));
        assertTrue(Files.exists(root.resolve(
                "shop-common/target/classes/com/example/shop/common/stereotype/UseCase.class")));
        assertTrue(Files.exists(root.resolve(
                "orders/orders-service/target/classes/application-dev.yml")), "resources copied");
    }

    @Test
    void reposHavePoms() {
        Path root = Fixture.shared();
        for (String repo : Fixture.REPOS) {
            assertTrue(Files.exists(root.resolve(repo).resolve("pom.xml")), repo);
        }
    }
}
