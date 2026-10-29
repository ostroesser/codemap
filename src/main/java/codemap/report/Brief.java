package codemap.report;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The paste-once instructions for the chat LLM: how to work with codemap, identifiers, and the
 * schema rendered from schema_doc. Must fit {@link #MAX_CHARS}; detail is dropped in steps
 * (table examples, then column notes, then view examples) until it does.
 */
public final class Brief {

    public static final int MAX_CHARS = 15_000;

    /** Views first: the LLM should reach for them before raw tables. */
    private static final List<String> ORDER = List.of(
            "entry_points", "message_flows", "http_flows", "calls_resolved", "module_links", "config_effective",
            "class_summary", "services", "service_modules", "repos", "modules", "classes", "methods", "calls",
            "type_hierarchy", "annotations", "annotation_meta", "fields", "class_dependencies", "beans", "injections",
            "bean_definitions", "injection_points", "endpoints", "listeners", "outbound", "config_properties",
            "entities", "entity_relations", "data_repositories", "maven_dependencies", "scan_warnings", "conditions");

    /** Families documented in one line instead of one entry each. */
    private static final String FAMILIES = """
            beans_effective, injections_effective, endpoints_effective, listeners_effective: same columns as the base \
            table; per service, runtime rows when present, else static. Prefer them.
            beans_compare(service, bean_name, class_fqn, presence), endpoints_compare(service, http_method, path_key, \
            path, method_id, presence): presence = both|static_only|runtime_only.""";

    /** Column descriptions that the Identifiers section already explains. */
    private static final Set<String> OBVIOUS = Set.of("service", "git repo", "module", "class", "method", "0/1",
            "kind", "role", "simple name", "static|runtime", "runtime");

    private static final String RULES = """
            # You are exploring a Spring Boot application with codemap
            You have no access to the code. All facts come from a SQLite database that I query for you with \
            codemap. Facts were extracted from compiled classes, POMs and application*.yml%s.

            ## How we work
            1. Answer only from facts pasted in this conversation. If a fact is missing, ask for it with a query. \
            Never invent class, method, table, topic, endpoint or config names.
            2. For list-all, count, "where is", "who calls", "what uses" questions, do not guess: emit SQL.
            3. Put your requests at the END of your answer: each query in a ```sql block (SQLite, SELECT/WITH only; \
            several queries per block are fine), commands in a ```cmd block. Batch up to ~8 requests per answer; \
            I paste back all results at once.
            4. Aggregate first (GROUP BY + COUNT), then drill down. Use LIMIT 50 on raw listings. Results are \
            capped at ~80k chars; "TRUNCATED: N more rows" means refine the query, or ask me for the next page.
            5. Prefer the views (entry_points, message_flows, http_flows, calls_resolved, module_links, \
            config_effective, class_summary, *_effective).
            6. Find names with LIKE on simple_name (classes) or method_id before assuming an fqn.
            7. Commands: `source <Class>`, `source <Class#method>` print code; `diagram <repo>`, \
            `diagram <repo> artifacts`, `diagram app` print PlantUML. Ask for source only when structure is not \
            enough.
            8. Static analysis can be ambiguous: injections.resolved_class NULL with candidates > 1, unresolved \
            ${...} in a path/topic, or a NULL side in a flow means "not determined". Say so instead of picking.%s
            9. End every answer with "Facts established:" and 3-8 terse bullets of what is now known for certain, \
            with exact names. These bullets are our shared memory; restate earlier ones when they still matter.

            ## Identifiers
            repo = git repository. module = Maven artifactId. service = deployable Spring Boot app \
            (spring.application.name). Class key = fqn ('$' for nested classes). method_id = \
            'fqn#name(ParamSimpleTypes)', constructors are '<init>'. Booleans are 0/1. role is one of controller, \
            service, repository, component, configuration, entity, client, listener, application (or a local \
            role). source = static|runtime.
            Not covered statically: beans from library auto-configuration, conditions evaluated at startup, \
            dynamic URLs/topics built at runtime.
            """;

    private final Db db;

    public Brief(Connection conn) {
        this.db = new Db(conn);
    }

    public String render() throws SQLException {
        Map<String, String[]> docs = new LinkedHashMap<>(); // object → {kind, description, example}
        Map<String, List<String[]>> columns = new LinkedHashMap<>();
        for (String[] r : db.rows("SELECT object, object_kind, column, description, example_query FROM schema_doc"
                + " ORDER BY rowid")) {
            if (r[2].isEmpty()) {
                docs.put(r[0], new String[]{r[1], r[3], r[4]});
            } else {
                columns.computeIfAbsent(r[0], k -> new ArrayList<>()).add(new String[]{r[2], r[3]});
            }
        }
        String text = null;
        for (int level = 0; level <= 3; level++) {
            text = compose(docs, columns, level);
            if (text.length() <= MAX_CHARS) {
                return text;
            }
        }
        return text.substring(0, MAX_CHARS - 80) + "\nTRUNCATED: brief cut at " + MAX_CHARS + " chars\n";
    }

    /**
     * level 0: everything; 1: no examples for tables; 2: no column notes either;
     * 3: no examples at all.
     */
    private String compose(Map<String, String[]> docs, Map<String, List<String[]>> columns, int level)
            throws SQLException {
        StringBuilder sb = new StringBuilder();
        List<String> runtime = new ArrayList<>();
        for (String[] r : db.rows("SELECT DISTINCT service FROM beans WHERE source = 'runtime' ORDER BY 1")) {
            runtime.add(r[0]);
        }
        String runtimeNote = runtime.isEmpty() ? ""
                : ", plus runtime actuator data for services " + String.join(", ", runtime);
        String runtimeRule = runtime.isEmpty() ? ""
                : " Rows with source='runtime' are observed truth (real wiring, effective config, auto-configuration"
                + " outcomes in conditions) and win over static rows.";
        sb.append(String.format(RULES, runtimeNote, runtimeRule));

        String app = appLine();
        if (app != null) {
            sb.append("\nThis database: ").append(app).append('\n');
        }

        sb.append("\n## Schema (table(columns) - purpose; notes on non-obvious columns; e.g. example)\n");
        Set<String> explained = new HashSet<>();
        for (String name : ORDER) {
            String[] d = docs.get(name);
            if (d == null) {
                continue;
            }
            List<String[]> cols = columns.getOrDefault(name, List.of());
            sb.append(name).append('(')
                    .append(String.join(", ", cols.stream().map(c -> c[0]).toList()))
                    .append(") - ").append(d[1]).append('\n');
            if (level < 2) {
                List<String> notes = new ArrayList<>();
                for (String[] c : cols) {
                    String note = c[0] + ": " + c[1];
                    if (informative(c[0], c[1]) && explained.add(note)) {
                        notes.add(note);
                    }
                }
                if (!notes.isEmpty()) {
                    sb.append("  ").append(String.join("; ", notes)).append('\n');
                }
            }
            boolean isView = "view".equals(d[0]);
            if (d[2] != null && (level == 0 || (isView && level < 3))) {
                sb.append("  e.g. ").append(d[2]).append('\n');
            }
        }
        sb.append(FAMILIES).append('\n');
        return sb.toString();
    }

    /** Notes worth their characters: value sets, NULL meaning, formats, or real explanations. */
    static boolean informative(String column, String description) {
        if (OBVIOUS.contains(description) || description.equals(column.replace('_', ' '))) {
            return false;
        }
        String d = description.toLowerCase();
        for (String marker : List.of("|", "null", "=", "(", "e.g", "key", "resolved", "raw", "transitive", "1 ",
                "#", "'", "json", "else")) {
            if (d.contains(marker)) {
                return true;
            }
        }
        return description.length() >= 32;
    }

    private String appLine() throws SQLException {
        List<String[]> apps = db.rows("SELECT app, COUNT(*) FROM repos GROUP BY app ORDER BY app");
        if (apps.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String[] a : apps) {
            List<String> services = new ArrayList<>();
            for (String[] s : db.rows("SELECT s.service FROM services s JOIN repos r ON r.repo = s.repo"
                    + " WHERE r.app = ? ORDER BY 1", a[0])) {
                services.add(s[0]);
            }
            parts.add("app '" + a[0] + "' with " + a[1] + " repos" + (services.isEmpty() ? ""
                    : ", services " + String.join(", ", services)));
        }
        return String.join("; ", parts) + ".";
    }
}
