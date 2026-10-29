package codemap.report;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PlantUML diagrams in C4 style (PlantUML's built-in C4 library, no network needed):
 * the application overview, a repo's components, and a repo's artifact graph.
 */
public final class Diagrams {

    /** Above this many components, a repo diagram shows packages instead of classes. */
    static final int MAX_COMPONENTS = 60;

    private static final List<String> SHOWN_ROLES = List.of("controller", "service", "client", "listener",
            "repository", "configuration", "component");

    private final Db db;

    public Diagrams(Connection conn) {
        this.db = new Db(conn);
    }

    /** Services as containers, HTTP calls and messaging between them (topics as queues). */
    public String overview(String app) throws SQLException {
        List<String> out = header("C4_Container", app + " overview");
        Set<String> services = new LinkedHashSet<>();
        for (String[] s : db.rows("SELECT s.service, s.repo FROM services s JOIN repos r ON r.repo = s.repo"
                + " WHERE r.app = ? ORDER BY s.service", app)) {
            services.add(s[0]);
            out.add("Container(" + id("svc", s[0]) + ", \"" + s[0] + "\", \"Spring Boot\", \"repo " + s[1] + "\")");
        }
        Set<String> declared = new LinkedHashSet<>();
        for (String[] f : db.rows("SELECT DISTINCT client_service, server_service, http_method, client_path"
                + " FROM http_flows WHERE client_service IS NOT NULL AND server_service IS NOT NULL"
                + " ORDER BY 1, 2, 4")) {
            if (services.contains(f[0]) && services.contains(f[1])) {
                out.add("Rel(" + id("svc", f[0]) + ", " + id("svc", f[1]) + ", \"" + nz(f[2]) + " " + f[3] + "\", \"HTTP\")");
            }
        }
        for (String[] f : db.rows("SELECT DISTINCT destination, kind, producer_service, consumer_service"
                + " FROM message_flows WHERE destination IS NOT NULL ORDER BY 1")) {
            String topic = id("topic", f[0]);
            if (declared.add(topic)) {
                out.add("ContainerQueue(" + topic + ", \"" + f[0] + "\", \"" + f[1] + "\")");
            }
            if (f[2] != null && services.contains(f[2])) {
                out.add("Rel(" + id("svc", f[2]) + ", " + topic + ", \"sends\")");
            }
            if (f[3] != null && services.contains(f[3])) {
                out.add("Rel(" + topic + ", " + id("svc", f[3]) + ", \"consumed by\")");
            }
        }
        return footer(out);
    }

    /** One boundary per module, main classes as components, injections as relations. */
    public String components(String repo) throws SQLException {
        List<String> out = header("C4_Component", "repo " + repo + " components");
        List<String[]> classes = db.rows("SELECT fqn, simple_name, module, role, package FROM classes"
                + " WHERE repo = ? AND is_external = 0 AND role IN ('" + String.join("', '", SHOWN_ROLES) + "')"
                + " ORDER BY module, role, simple_name", repo);
        if (classes.isEmpty()) {
            out.add("note \"No Spring components found in repo " + repo + "\" as N1");
            return footer(out);
        }
        boolean byPackage = classes.size() > MAX_COMPONENTS;
        Map<String, List<String[]>> byModule = new LinkedHashMap<>();
        for (String[] c : classes) {
            byModule.computeIfAbsent(c[2], k -> new ArrayList<>()).add(c);
        }
        Map<String, String> nodeOf = new LinkedHashMap<>();
        for (var e : byModule.entrySet()) {
            out.add("Container_Boundary(" + id("mod", e.getKey()) + ", \"" + e.getKey() + "\") {");
            if (byPackage) {
                Map<String, Integer> packages = new LinkedHashMap<>();
                for (String[] c : e.getValue()) {
                    packages.merge(c[4], 1, Integer::sum);
                    nodeOf.put(c[0], id("pkg", c[4]));
                }
                for (var p : packages.entrySet()) {
                    out.add("  Component(" + id("pkg", p.getKey()) + ", \"" + p.getKey() + "\", \"package\", \""
                            + p.getValue() + " components\")");
                }
            } else {
                for (String[] c : e.getValue()) {
                    nodeOf.put(c[0], id("cls", c[0]));
                    out.add("  Component(" + id("cls", c[0]) + ", \"" + c[1] + "\", \"" + c[3] + "\")");
                }
            }
            out.add("}");
        }
        Set<String> rels = new LinkedHashSet<>();
        for (String[] i : db.rows("SELECT DISTINCT consumer_class, resolved_class FROM injections_effective"
                + " WHERE resolved_class IS NOT NULL")) {
            String from = nodeOf.get(i[0]);
            String to = nodeOf.get(i[1]);
            if (from != null && to != null && !from.equals(to) && rels.add(from + to)) {
                out.add("Rel(" + from + ", " + to + ", \"uses\")");
            }
        }
        // Calls into the declared client interfaces of other repos, and topics this repo talks to.
        for (String[] o : db.rows("SELECT DISTINCT o.kind, o.target, c.module FROM outbound o"
                + " JOIN classes c ON c.fqn = o.class_fqn WHERE c.repo = ? AND o.target IS NOT NULL"
                + " AND o.kind IN ('kafka', 'jms', 'rabbit', 'stream')", repo)) {
            String topic = id("ext", o[1]);
            if (rels.add(topic)) {
                out.add("ComponentQueue_Ext(" + topic + ", \"" + o[1] + "\", \"" + o[0] + "\")");
            }
            out.add("Rel(" + id("mod", o[2]) + ", " + topic + ", \"sends\")");
        }
        for (String[] l : db.rows("SELECT DISTINCT l.kind, l.destination, l.class_fqn FROM listeners_effective l"
                + " JOIN classes c ON c.fqn = l.class_fqn WHERE c.repo = ? AND l.destination IS NOT NULL", repo)) {
            String topic = id("ext", l[1]);
            if (rels.add(topic)) {
                out.add("ComponentQueue_Ext(" + topic + ", \"" + l[1] + "\", \"" + l[0] + "\")");
            }
            String to = nodeOf.get(l[2]);
            if (to != null) {
                out.add("Rel(" + topic + ", " + to + ", \"consumed by\")");
            }
        }
        if (byPackage) {
            out.add("note \"" + classes.size() + " components summarised by package\" as N1");
        }
        return footer(out);
    }

    /** Modules of the repo, the modules they depend on and those depending on them. */
    public String artifacts(String repo) throws SQLException {
        List<String> out = header("C4_Component", "repo " + repo + " artifacts");
        Set<String> modules = new LinkedHashSet<>();
        List<String[]> links = db.rows("SELECT from_repo, from_module, to_repo, to_module, scope FROM module_links"
                + " WHERE from_repo = ? OR to_repo = ? ORDER BY 1, 2, 4", repo, repo);
        for (String[] m : db.rows("SELECT module, packaging, is_service FROM modules WHERE repo = ? ORDER BY path", repo)) {
            modules.add(m[0]);
        }
        Map<String, Set<String>> byRepo = new LinkedHashMap<>();
        byRepo.put(repo, new LinkedHashSet<>(modules));
        for (String[] l : links) {
            byRepo.computeIfAbsent(l[0], k -> new LinkedHashSet<>()).add(l[1]);
            byRepo.computeIfAbsent(l[2], k -> new LinkedHashSet<>()).add(l[3]);
        }
        for (var e : byRepo.entrySet()) {
            String kind = e.getKey().equals(repo) ? "System_Boundary" : "Boundary";
            out.add(kind + "(" + id("repo", e.getKey()) + ", \"" + e.getKey() + "\") {");
            for (String m : e.getValue()) {
                String packaging = db.one("SELECT CASE WHEN is_service = 1 THEN 'service' ELSE packaging END"
                        + " FROM modules WHERE module = ?", m);
                String macro = e.getKey().equals(repo) ? "Component" : "Component_Ext";
                out.add("  " + macro + "(" + id("mod", m) + ", \"" + m + "\", \"" + nz(packaging) + "\")");
            }
            out.add("}");
        }
        for (String[] l : links) {
            out.add("Rel(" + id("mod", l[1]) + ", " + id("mod", l[3]) + ", \"" + nz(l[4]) + "\")");
        }
        return footer(out);
    }

    private static List<String> header(String library, String title) {
        List<String> out = new ArrayList<>();
        out.add("@startuml");
        out.add("!include <C4/" + library + ">");
        out.add("title " + title);
        return out;
    }

    private static String footer(List<String> out) {
        out.add("SHOW_LEGEND()");
        out.add("@enduml");
        return String.join("\n", out) + "\n";
    }

    static String id(String prefix, String name) {
        return prefix + "_" + name.replaceAll("[^A-Za-z0-9]", "_");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
