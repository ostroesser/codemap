package codemap.fixture;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Synthetic multi-repo workspace in a neutral "shop" domain:
 * <ul>
 *   <li>{@code shop-common}: library with a custom stereotype, an {@code @HttpExchange} client,
 *       an event record and topic constants</li>
 *   <li>{@code orders}: parent POM with {@code orders-domain} (JPA) and {@code orders-service}
 *       (REST, Kafka sends, scheduled job, event listener, {@code @Bean} client)</li>
 *   <li>{@code catalog}: single-module service (REST, Kafka listener with {@code ${}} topic,
 *       JPA, custom stereotype, ambiguous injection resolved by {@code @Primary})</li>
 * </ul>
 * Sources live in {@code src/test/resources/fixture}; they are copied and compiled in-process
 * into each module's {@code target/classes}, with {@code -g -parameters} like a Spring Boot build.
 */
public final class Fixture {

    /** Modules in build order: path relative to the workspace, and internal module dependencies. */
    public record Module(String path, List<String> dependsOn) {
    }

    public static final List<Module> MODULES = List.of(
            new Module("shop-common", List.of()),
            new Module("orders/orders-domain", List.of()),
            new Module("orders/orders-service", List.of("orders/orders-domain", "shop-common")),
            new Module("catalog", List.of("shop-common")));

    public static final List<String> REPOS = List.of("shop-common", "orders", "catalog");

    private static Path shared;

    private Fixture() {
    }

    /** Builds the workspace once per JVM under target/fixture-workspace. Treat it as read-only. */
    public static synchronized Path shared() {
        if (shared == null) {
            shared = build(Path.of("target", "fixture-workspace").toAbsolutePath());
        }
        return shared;
    }

    /** Copies the fixture sources into {@code root} (wiped first) and compiles every module. */
    public static Path build(Path root) {
        try {
            deleteRecursively(root);
            copyTree(sourceRoot(), root);
            for (Module module : MODULES) {
                compile(root, module);
            }
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void compile(Path root, Module module) throws IOException {
        Path moduleDir = root.resolve(module.path());
        Path srcDir = moduleDir.resolve("src/main/java");
        Path classesDir = moduleDir.resolve("target/classes");
        Files.createDirectories(classesDir);

        List<File> sources;
        try (Stream<Path> files = Files.walk(srcDir)) {
            sources = files.filter(p -> p.toString().endsWith(".java")).map(Path::toFile).toList();
        }

        List<String> classpath = new ArrayList<>();
        for (String dep : module.dependsOn()) {
            classpath.add(root.resolve(dep).resolve("target/classes").toString());
        }
        classpath.add(testClasspath());

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        StringWriter diagnostics = new StringWriter();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, null)) {
            List<String> options = List.of(
                    "-g", "-parameters", "--release", "21", "-proc:none",
                    "-classpath", String.join(File.pathSeparator, classpath),
                    "-d", classesDir.toString());
            boolean ok = javac.getTask(diagnostics, fm, null, options, null,
                    fm.getJavaFileObjectsFromFiles(sources)).call();
            if (!ok) {
                throw new IllegalStateException("Fixture module " + module.path()
                        + " failed to compile:\n" + diagnostics);
            }
        }

        Path resources = moduleDir.resolve("src/main/resources");
        if (Files.isDirectory(resources)) {
            copyTree(resources, classesDir);
        }
    }

    /** Surefire hides the real classpath behind a manifest-only jar; it publishes it here. */
    private static String testClasspath() {
        String cp = System.getProperty("surefire.test.class.path");
        return cp != null && !cp.isBlank() ? cp : System.getProperty("java.class.path");
    }

    private static Path sourceRoot() {
        try {
            var url = Fixture.class.getResource("/fixture");
            if (url == null) {
                throw new IllegalStateException("fixture resources not on the test classpath");
            }
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }
}
