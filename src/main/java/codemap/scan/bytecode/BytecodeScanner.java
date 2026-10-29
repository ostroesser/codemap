package codemap.scan.bytecode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import codemap.db.BatchInserter;
import codemap.scan.LocalConfig;
import codemap.scan.bytecode.ClassFact.AnnotationFact;
import codemap.scan.bytecode.ClassFact.CallFact;
import codemap.scan.bytecode.ClassFact.FieldFact;
import codemap.scan.bytecode.ClassFact.MethodFact;

/** Reads a module's target/classes and writes its code-structure rows. */
public final class BytecodeScanner {

    /** Owner of an internal class: who wrote it first in this database. */
    public record Owner(String repo, String module) {
    }

    private final Connection conn;
    private final LocalConfig config;
    private final Set<String> internalFqns;
    private final Map<String, Owner> owners;
    private final Consumer<String> duplicateWarning;

    /**
     * @param internalFqns every class of every repo in the workspace (from file names), so calls
     *                     into repos scanned later are kept
     * @param owners       fqn → owner of classes already in the database; updated as we write
     */
    public BytecodeScanner(Connection conn, LocalConfig config, Set<String> internalFqns, Map<String, Owner> owners,
                           Consumer<String> duplicateWarning) {
        this.conn = conn;
        this.config = config;
        this.internalFqns = internalFqns;
        this.owners = owners;
        this.duplicateWarning = duplicateWarning;
    }

    /** Fqns of all classes under a target/classes dir, derived from file names only. */
    public static Set<String> classNames(Path classesDir) throws IOException {
        Set<String> names = new HashSet<>();
        if (!Files.isDirectory(classesDir)) {
            return names;
        }
        try (Stream<Path> s = Files.walk(classesDir)) {
            s.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> classesDir.relativize(p).toString())
                    .filter(n -> !n.endsWith("module-info.class") && !n.endsWith("package-info.class"))
                    .map(n -> n.substring(0, n.length() - ".class".length()).replace('\\', '.').replace('/', '.'))
                    .forEach(names::add);
        }
        return names;
    }

    public List<ClassFact> scanModule(String repo, String module, Path repoDir, Path moduleDir)
            throws IOException, SQLException {
        Path classesDir = moduleDir.resolve("target/classes");
        List<ClassFact> facts = new ArrayList<>();
        if (!Files.isDirectory(classesDir)) {
            return facts;
        }
        try (Stream<Path> s = Files.walk(classesDir)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".class")).sorted()::iterator) {
                String name = p.getFileName().toString();
                if (name.equals("module-info.class") || name.equals("package-info.class")) {
                    continue;
                }
                ClassFact fact = ClassFactReader.read(Files.readAllBytes(p));
                Owner existing = owners.get(fact.fqn);
                if (existing != null) {
                    duplicateWarning.accept(fact.fqn + " also in module " + existing.module() + "; kept that one");
                    continue;
                }
                owners.put(fact.fqn, new Owner(repo, module));
                facts.add(fact);
            }
        }
        write(repo, module, repoDir, moduleDir, facts);
        return facts;
    }

    private void write(String repo, String module, Path repoDir, Path moduleDir, List<ClassFact> facts)
            throws SQLException {
        String modulePrefix = repoDir.relativize(moduleDir).toString().replace('\\', '/');
        modulePrefix = modulePrefix.isEmpty() ? "" : modulePrefix + "/";
        try (var classes = new BatchInserter(conn, "classes", "fqn", "simple_name", "package", "repo", "module",
                "kind", "visibility", "is_abstract", "superclass", "source_file", "first_line", "last_line",
                "is_external");
             var hierarchy = BatchInserter.insertOrIgnore(conn, "type_hierarchy", "class_fqn", "supertype_fqn",
                     "relation", "depth");
             var annotations = new BatchInserter(conn, "annotations", "target_kind", "target", "class_fqn",
                     "annotation_fqn", "annotation", "attributes");
             var fields = BatchInserter.insertOrIgnore(conn, "fields", "class_fqn", "name", "type", "type_fqn",
                     "is_static", "is_final");
             var methods = BatchInserter.insertOrIgnore(conn, "methods", "method_id", "class_fqn", "name",
                     "signature", "return_type", "visibility", "is_static", "is_abstract", "first_line", "last_line");
             var calls = new BatchInserter(conn, "calls", "caller_class", "caller_method", "callee_class",
                     "callee_method", "line", "invoke_kind");
             var deps = BatchInserter.insertOrIgnore(conn, "class_dependencies", "from_class", "to_class", "weight")) {

            for (ClassFact c : facts) {
                int first = 0;
                int last = 0;
                for (MethodFact m : c.methods) {
                    if (m.firstLine > 0) {
                        first = first == 0 ? m.firstLine : Math.min(first, m.firstLine);
                        last = Math.max(last, m.lastLine);
                    }
                }
                String pkg = TypeNames.packageOf(c.fqn);
                String sourceFile = null;
                if (c.sourceFileName != null) {
                    String rel = "src/main/java/" + (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/") + c.sourceFileName;
                    if (Files.isRegularFile(moduleDir.resolve(rel))) {
                        sourceFile = modulePrefix + rel;
                    }
                }
                String simple = c.fqn.substring(pkg.isEmpty() ? 0 : pkg.length() + 1);
                classes.add(c.fqn, simple, pkg, repo, module, c.kind, c.visibility, c.isAbstract, c.superclass,
                        sourceFile, first == 0 ? null : first, last == 0 ? null : last, 0);

                if (c.superclass != null) {
                    hierarchy.add(c.fqn, c.superclass, "extends", 1);
                }
                for (String i : c.interfaces) {
                    hierarchy.add(c.fqn, i, "implements", 1);
                }
                for (AnnotationFact a : c.annotations) {
                    annotation(annotations, "class", c.fqn, c.fqn, a);
                }
                for (FieldFact f : c.fields) {
                    fields.add(c.fqn, f.name, f.type, f.typeFqn, f.isStatic, f.isFinal);
                    for (AnnotationFact a : f.annotations) {
                        annotation(annotations, "field", c.fqn + "." + f.name, c.fqn, a);
                    }
                }
                for (MethodFact m : c.methods) {
                    methods.add(m.id, c.fqn, m.name, m.readableSignature(), m.returnType, m.visibility, m.isStatic,
                            m.isAbstract, m.firstLine == 0 ? null : m.firstLine, m.lastLine == 0 ? null : m.lastLine);
                    for (AnnotationFact a : m.annotations) {
                        annotation(annotations, "method", m.id, c.fqn, a);
                    }
                    for (int i = 0; i < m.parameterAnnotations.size(); i++) {
                        for (AnnotationFact a : m.parameterAnnotations.get(i)) {
                            annotation(annotations, "parameter", m.id + "/" + i, c.fqn, a);
                        }
                    }
                    Set<String> seen = new HashSet<>();
                    for (CallFact call : m.calls) {
                        boolean keep = internalFqns.contains(call.ownerFqn())
                                || config.keepsCall(call.ownerFqn(), call.calleeId());
                        if (keep && seen.add(call.calleeId() + "@" + call.line())) {
                            calls.add(c.fqn, m.id, call.ownerFqn(), call.calleeId(), call.line() == 0 ? null : call.line(),
                                    call.invokeKind());
                        }
                    }
                }
                for (var ref : c.referencedTypes.entrySet()) {
                    String to = TypeNames.fqn(ref.getKey());
                    if (!to.equals(c.fqn) && internalFqns.contains(to)) {
                        deps.add(c.fqn, to, ref.getValue());
                    }
                }
            }
        }
    }

    private static void annotation(BatchInserter out, String kind, String target, String classFqn, AnnotationFact a)
            throws SQLException {
        out.add(kind, target, classFqn, a.fqn(), TypeNames.simple(a.fqn()),
                a.attributes().isEmpty() ? null : Json.write(a.attributes()));
    }
}
