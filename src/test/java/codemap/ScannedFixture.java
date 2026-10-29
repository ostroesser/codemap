package codemap;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import codemap.fixture.Fixture;
import codemap.scan.LocalConfig;
import codemap.scan.Scanner;

/** The fixture workspace scanned once per JVM with default settings. Treat as read-only. */
public final class ScannedFixture {

    private static Path db;

    private ScannedFixture() {
    }

    public static synchronized Path db() {
        if (db == null) {
            try {
                Path dir = Path.of("target", "scanned-fixture").toAbsolutePath();
                Fixture.deleteRecursively(dir);
                Files.createDirectories(dir);
                Path file = dir.resolve("codemap.db");
                Scanner.scan(file, List.of(Fixture.shared()), quiet(LocalConfig.defaults()));
                db = file;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return db;
    }

    public static Scanner.Options quiet(LocalConfig config) {
        return new Scanner.Options("shop", false, config, new PrintStream(new ByteArrayOutputStream()));
    }
}
