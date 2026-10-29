package codemap.scan.bytecode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lazily loads headers (supertypes + class annotations) of classes outside the scanned code,
 * from dependency jars. Jars come from per-module classpath files written by
 * {@code mvn dependency:build-classpath -Dmdep.outputFile=target/codemap.classpath}.
 * Jar entry lists are indexed on first lookup; only requested classes are parsed.
 */
public final class ClasspathIndex implements AutoCloseable {

    public static final String CLASSPATH_FILE = "target/codemap.classpath";

    private final Set<Path> jars = new LinkedHashSet<>();
    private Map<String, Path> entries;
    private final Map<Path, ZipFile> open = new HashMap<>();
    private final Map<String, Optional<ClassFact>> cache = new HashMap<>();

    /** Adds jars listed in a module's classpath file, if it exists. Returns false if absent. */
    public boolean addClasspathFile(Path moduleDir) throws IOException {
        Path file = moduleDir.resolve(CLASSPATH_FILE);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        String text = Files.readString(file).strip();
        String separator = text.contains(";") && !text.contains(":/") ? ";" : java.io.File.pathSeparator;
        for (String part : text.split(separator)) {
            if (!part.isBlank()) {
                addJar(Path.of(part.strip()));
            }
        }
        return true;
    }

    public void addJar(Path jar) {
        if (jar.toString().endsWith(".jar") && Files.isRegularFile(jar)) {
            jars.add(jar.toAbsolutePath().normalize());
            entries = null;
        }
    }

    public int jarCount() {
        return jars.size();
    }

    /** Header of an external class, or empty if it is not on the indexed classpath. */
    public Optional<ClassFact> header(String fqn) {
        return cache.computeIfAbsent(fqn, this::load);
    }

    private Optional<ClassFact> load(String fqn) {
        if (entries == null) {
            index();
        }
        String entryName = fqn.replace('.', '/') + ".class";
        Path jar = entries.get(entryName);
        if (jar == null) {
            return Optional.empty();
        }
        try {
            ZipFile zip = open.computeIfAbsent(jar, ClasspathIndex::openZip);
            ZipEntry entry = zip.getEntry(entryName);
            try (InputStream in = zip.getInputStream(entry)) {
                return Optional.of(ClassFactReader.readHeader(in.readAllBytes()));
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private void index() {
        entries = new HashMap<>();
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                zip.stream()
                        .map(ZipEntry::getName)
                        .filter(n -> n.endsWith(".class") && !n.startsWith("META-INF/"))
                        .forEach(n -> entries.putIfAbsent(n, jar));
            } catch (IOException e) {
                // unreadable jar: skip
            }
        }
    }

    private static ZipFile openZip(Path jar) {
        try {
            return new ZipFile(jar.toFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        for (ZipFile z : open.values()) {
            try {
                z.close();
            } catch (IOException ignored) {
                // closing a read-only jar
            }
        }
        open.clear();
    }
}
