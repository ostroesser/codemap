# codemap — design

## Decisions
- Class key is the fqn alone; duplicate fqns across modules produce a `scan_warnings` row.
- Beans, injections, endpoints and listeners are scoped per `service` (deployable module);
  `import-actuator` takes `--service`.
- `calls` stores internal callees plus an allow-list of integration APIs only.
- Bean and injection facts are first recorded per module (`bean_definitions`,
  `injection_points`), then scoped per service by a workspace-wide pass after every scan.
- Runtime import (v2): each actuator file replaces that service's runtime rows of the same
  kind; `listeners_effective` prefers runtime rows per (service, kind), since actuator reports
  scheduled tasks but not message listeners.
- ClassGraph was dropped: one ASM pass per class, plus lazy loading of dependency-jar headers
  from `target/codemap.classpath` for meta-annotations and supertypes.

## 1. Library choices

| Need | Proposal | Notes / challenge |
|---|---|---|
| CLI | picocli 4.7 | Keep. |
| Class scan | **ASM only** (drop ClassGraph) | ASM is needed anyway for method bodies. Parsing every class once with ASM gives annotations, generic signatures, hierarchy, line numbers and calls in a single pass. ClassGraph would parse everything a second time. A small lazy **classpath index** (jar → entries) loads *external* classes on demand, for meta-annotations on framework annotations and for supertypes of framework base classes. |
| Classpath of external jars | `mvn dependency:build-classpath` output per module (optional `--classpath-from-maven`, or a file you pass in), falling back to `~/.m2` lookups by POM coordinates | Meta-annotation resolution needs the annotation classes from dependency jars (`@Service` lives in spring-context, custom stereotypes live in framework jars). Only annotation and supertype classes are loaded, on demand, so memory stays low. |
| POM | maven-model 3.9 (`MavenXpp3Reader`) | Raw POMs, plus our own parent/property/dependencyManagement resolution across the scanned repos. No full effective POM (that would need Maven resolution); internal links only need `groupId:artifactId`. |
| YAML | SnakeYAML 2.x | Handles multi-document files and `spring.config.activate.on-profile`. |
| DB | sqlite-jdbc | Ships aarch64 and x86_64 natives. `run` opens the DB **read-only** (`query_only`). |
| Source ranges | Bytecode `LineNumberTable` plus a text heuristic | Bytecode gives the first and last *executable* line. `source` walks back to the declaration line (annotations and signature) and forward to the closing brace. JavaParser would be exact but adds weight; revisit only if the heuristic fails. |
| Clipboard (WSL) | Read: `powershell.exe -NoProfile -Command "[Console]::OutputEncoding=[Text.Encoding]::UTF8; Get-Clipboard -Raw"`. Write: UTF-16LE with BOM piped into `clip.exe` | Plain `clip.exe` mangles non-ASCII characters in UTF-8 input. A `--out FILE` / stdout fallback is always available. |
| Fat jar | maven-shade-plugin | |

Memory: one class is visited at a time. Inserts are batched per module, in one transaction per repo.
Hierarchy and meta-annotation closures are computed in SQL afterwards with recursive CTEs.

## 2. Ideas borrowed from jQAssistant

- **Project vs artifact**: a module *creates* an artifact, and dependencies point at artifacts.
  That is how a cross-repo link is detected: a dependency's `groupId:artifactId` is created by a
  scanned module.
- **External types are first-class but thin**: classes that are referenced but not scanned keep
  only their fqn (`is_external = 1`). Hierarchy rows through them (e.g. `JpaRepository`) stay
  queryable.
- **Annotations as values with attributes**: stored as JSON per annotation usage, plus a
  transitive **meta-annotation closure** (jQA has no closure; we add one because stereotype
  resolution needs it).
- **INVOKES with line number** → `calls.line`. **Virtual invokes** (jQA concept
  `java:VirtualInvokes`) → view `calls_resolved`, which expands interface/abstract calls to
  concrete implementations.
- **DEPENDS_ON with weight** → `class_dependencies.weight` (internal classes only).
- **Natural keys instead of node ids**: fqn and readable method ids, so the LLM never has to
  join on surrogate integers.

What we skip: generic Bound graphs (flattened to a readable string), local variables,
field reads and writes, complexity metrics (could come later).

## 3. Schema

Conventions (repeated in the brief):
- `repo` = git repository (folder name). `module` = Maven artifactId (unique within an app).
  `service` = a deployable Spring Boot module (it has `@SpringBootApplication`).
- Class key = **fqn**. Method key `method_id` = `fqn#name(ParamSimpleTypes)`, e.g.
  `com.example.shop.order.OrderService#place(OrderRequest,String)`.
- Booleans are 0/1. `source` ∈ (`static`, `runtime`).
- Every row carries `repo`, so a rescan deletes and reinserts by repo.

```sql
-- ── scan metadata ───────────────────────────────────────────────
repos(repo PK, app, path, git_commit, git_dirty, scanned_at, module_count, class_count)
modules(module PK, repo, group_id, artifact_id, version, packaging, parent,   -- parent = g:a
        path, has_classes, is_service)
services(service PK, repo, module, main_class, scan_packages)   -- @SpringBootApplication
scan_warnings(repo, module, kind, message)   -- e.g. no target/classes, unresolved ${}, duplicate fqn

-- ── maven ──────────────────────────────────────────────────────
maven_dependencies(module, repo, group_id, artifact_id, version, scope, optional,
                   internal_module)          -- non-null when produced by a scanned module
-- view  module_links(from_repo, from_module, to_repo, to_module, scope, cross_repo)

-- ── code structure ──────────────────────────────────────────────
classes(fqn PK, simple_name, package, repo, module, kind,  -- class|interface|enum|record|annotation
        visibility, is_abstract, superclass, role, role_reason,
        source_file, first_line, last_line, is_external)
type_hierarchy(class_fqn, supertype_fqn, relation, depth)   -- transitive; relation extends|implements
annotations(target_kind, target, class_fqn, annotation_fqn, annotation, attributes)
        -- target_kind class|method|field|parameter; attributes = JSON
annotation_meta(annotation_fqn, meta_annotation_fqn, depth)  -- transitive meta-annotations
fields(class_fqn, name, type, type_fqn, is_static, is_final)
methods(method_id PK, class_fqn, name, signature, return_type, visibility,
        is_static, is_abstract, first_line, last_line)
        -- signature is readable: 'List<Order> findByCustomer(Long customerId)'
calls(caller_method, callee_class, callee_method, line, invoke_kind)
        -- internal callees + an allow-list of integration APIs (templates, clients)
class_dependencies(from_class, to_class, weight)            -- internal only
-- view calls_resolved(caller_method, callee_method, target_method, via_interface)

-- ── spring (all carry `source`) ──────────────────────────────────
beans(service, bean_name, class_fqn, repo, module, role, declared_by,  -- scan|bean_method|auto_config
      factory_method, scope, is_primary, conditions, source)
injections(service, consumer_class, point, member, required_type, qualifier,
           resolved_class, candidates, source)   -- point constructor|field|setter|bean_method_param
endpoints(service, http_method, path, class_fqn, method_id, produces, consumes,
          path_expr, source)                     -- path = composed + resolved; path_expr = raw
listeners(service, kind, destination, destination_expr, group_id, class_fqn, method_id,
          payload_type, schedule, source)        -- kind kafka|jms|rabbit|event|scheduled
outbound(repo, module, kind, class_fqn, method_id, line, http_method, target,
         target_expr, client_interface)
         -- kind http_exchange|feign|rest_client|web_client|rest_template|kafka|jms|rabbit
config_properties(repo, module, file, profile, key, value, source)  -- profile '(default)' if none
-- runtime only (v2): conditions(service, condition_source, outcome, message)

-- ── persistence ────────────────────────────────────────────────
entities(class_fqn PK, repo, module, table_name, schema_name)
entity_relations(entity_fqn, field, relation, target_entity, mapped_by)  -- one_to_many …
data_repositories(class_fqn PK, repo, module, entity_fqn, id_type, base_interface)

-- ── self-description (feeds `brief`) ─────────────────────────────
schema_doc(object, column, description, example_query)
```

### Views (the LLM is told to prefer these)
| View | Purpose |
|---|---|
| `module_links` | Maven artifact graph, `cross_repo = 1` for links between repos |
| `entry_points` | endpoints ∪ listeners, one row per entry, with `kind` and `trigger` |
| `calls_resolved` | calls with interface → implementation expansion |
| `message_flows` | producer (`outbound` kafka/jms/rabbit) → destination → consumer (`listeners`), joined on resolved destination |
| `http_flows` | `outbound` http clients → matching `endpoints` (path template match, best effort, `match_quality`) |
| `config_effective` | per repo × profile × key: the profile value, otherwise the default |
| `beans_effective`, `injections_effective`, `endpoints_effective`, `listeners_effective` | runtime rows for a service when any exist, otherwise static |
| `beans_compare` (likewise for endpoints) | `static_only` / `runtime_only` / `both` |
| `class_summary` | class plus role, method count, inbound/outbound call counts, injected-into count |

### Role derivation (first match wins)
1. `codemap.local.yml` rules: by annotation fqn (meta-resolved), by superclass/interface, by
   package glob.
2. Built-in rules: `@RestController`/`@Controller` → controller, `@Configuration` →
   configuration, `@Entity` → entity, Spring Data `Repository` subtype or `@Repository` →
   repository, `@HttpExchange`/`@FeignClient` interface → client, class with listener methods →
   listener, `@Service` → service, `@Component` → component.
   The most specific stereotype wins: `@Service` is itself a `@Component`.
3. `role_reason` records why, e.g. `meta:@Service via com.example.fw.UseCase`.

`codemap.local.yml` (git-ignored; a generic `codemap.example.yml` is committed):
```yaml
roles:
  - annotation: com.example.fw.UseCase     # meta-annotated or not
    role: service
  - extends: com.example.fw.AbstractHandler
    role: listener
  - package: "*.adapter.out.*"
    role: client
endpoint_annotations: []   # extension point for custom mapping annotations (later)
```

### Placeholder resolution
`${a.b:default}` is resolved against the module's `(default)` profile config, then against
config of internal dependency modules, then the inline default. If profiles disagree on the
value, the default-profile value is stored and a `scan_warnings` row with kind
`profile_dependent` is added. The raw expression is always kept in the `*_expr` column.

### Static bean & injection resolution
For each service, candidate beans are scanned classes in the service module and its internal
dependency modules (transitively) under `scan_packages`, plus `@Bean` methods of the
`@Configuration` classes found there. For an injection point, candidates are the beans
assignable to `required_type` (through `type_hierarchy`). One candidate → `resolved_class`.
Several → narrowed by `@Qualifier`, `@Primary`, then parameter or field name. `candidates`
holds the count. Runtime import (v2) replaces guesswork with real wiring.

## 4. The brief (draft wording, ~9–11k chars once the schema is appended)

```
# You are exploring a Spring Boot application with codemap
You have no repo access. All facts come from a SQLite database that I query for you.
Facts were extracted from compiled code, POMs and application*.yml{{, plus runtime
actuator dumps for: SERVICES}}.

## Rules
1. Answer only from facts I have pasted in this conversation. If a fact is missing, ask for it
   with a query. Never invent class, method, table, topic or endpoint names.
2. For list-all, count, "where is", "who calls" or "what uses" questions, do not guess:
   emit SQL.
3. Put all your queries at the END of your answer, each in its own ```sql block
   (SQLite dialect, SELECT/WITH only). Batch up to ~8 queries per answer. I paste back all
   results at once.
4. Aggregate first (GROUP BY repo/role/kind, COUNT), then drill down. Use LIMIT 50 on raw
   listings. Results are capped at ~80k chars; a line "TRUNCATED: N more rows" means refine
   the query or ask me for the next page.
5. Prefer views: entry_points, calls_resolved, message_flows, http_flows, module_links,
   config_effective, class_summary{{, *_effective}}.
6. Match names with LIKE '%Order%' on simple_name before assuming an fqn.
7. To read code, emit a ```cmd block: `source com.example.Foo#bar` (one method) or
   `source com.example.Foo` (whole class). For a diagram: `diagram <repo>`.
   Ask for source only when structure is not enough; it is expensive.
8. Static resolution can be ambiguous: injections.candidates > 1 or a NULL resolved_class
   means unresolved. Say so rather than picking one.{{ Rows with source='runtime' are
   observed truth and win over static.}}
9. End every answer with "Facts established:" followed by 3–8 terse bullets of what is now
   known for certain (with names). These bullets are our shared memory; restate earlier
   ones when they matter.

## Identifiers
repo = git repo; module = Maven artifactId; service = deployable Spring Boot module.
Class key = fqn. method_id = 'fqn#name(ParamSimpleTypes)'. Booleans are 0/1.

## Schema
{{generated from schema_doc: one line per table: name(col, col, …) — purpose}}

## Example queries (one per table)
{{generated from schema_doc.example_query}}
```

Example of the generated part:
```
endpoints(service, http_method, path, class_fqn, method_id, produces, consumes, path_expr, source) — HTTP entry points
  e.g. SELECT service, http_method, path, method_id FROM endpoints WHERE path LIKE '/orders%';
message_flows(destination, kind, producer_method, producer_service, consumer_method, consumer_service)
  e.g. SELECT * FROM message_flows WHERE destination LIKE '%order%';
```

## 5. The digest (≤ 80k chars, degrades gracefully)

Sections, in priority order. When over budget, lower sections are summarised first:
1. App header: counts (repos, modules, services, classes, endpoints, listeners).
2. Repos: one line each, with the services in it, a guessed role (service / library / bom /
   parent from packaging and content), and its top packages.
3. Artifact graph: cross-repo `module_links` as an adjacency list.
4. Entry points per service: endpoints grouped `METHOD path → Class#method`, then listeners.
   Degradation: group by class-level path prefix with counts.
5. Integration: message_flows and http_flows (who talks to whom).
6. Main components per repo: controllers, services, clients, listeners, repositories →
   entity. Degradation: by package with counts per role, then by repo.
7. Persistence: entities with tables and relations.
8. PlantUML overview: services, topics and cross-service HTTP edges.

Every degradation step adds a line such as `(summarised: 312 classes in 41 packages; query
classes for detail)`.

## 6. `run` output format

```
### Q1 (17 rows)
| service | http_method | path |
|---|---|---|
...
### Q2 — ERROR: no such column: foo  (hint: columns of endpoints are ...)
### cmd: source com.example.shop.order.OrderService#place
```java
// order-service/src/main/java/com/example/shop/order/OrderService.java:42-61
...
```
TRUNCATED: 230 more rows in Q3, refine or use `codemap run --page 2`
```
- Values longer than 200 chars are cut with `…`. Repeated fqn prefixes are shortened only when
  unambiguous (off by default; flag `--short-names`).
- Each query gets a 30k-char share of the 80k budget, with unused share redistributed. The last
  run is cached in a session file so `--page N` works without the clipboard.
- SQL errors return the table's column list so the LLM can self-correct.

## 7. Milestones (tests green at each)

| # | Scope | Tests |
|---|---|---|
| M0 | Maven skeleton, picocli, fat jar, schema DDL + `schema_doc`, **fixture builder**: writes 3 repos (shop domain: `catalog`, `orders`, `shop-common`) with POMs, yml and Java sources, compiled in-process with `javax.tools` against test-scope Spring/Jakarta jars, so no `mvn` runs during tests | schema creates; fixture compiles |
| M1 | Maven + yml scan: repos, modules, dependencies, internal links, config flattening per profile, git commit and incremental skip, warnings | module_links, config per profile, skip on unchanged commit |
| M2 | Bytecode: classes, hierarchy closure, annotations + meta closure, methods, fields, calls, line numbers, classpath index, roles + `codemap.local.yml` | custom stereotype → service, interface call resolution |
| M3 | Spring facts: services, beans, injections, endpoints, listeners (`${}` topic), outbound (HttpExchange, KafkaTemplate constant/property), entities, data repositories, flow views | endpoint composition, message_flows across repos |
| M4 | `run`: block extraction, read-only execution, markdown formatting, budget, paging, clipboard I/O, `cmd source` / `cmd diagram` | golden-output tests, truncation |
| M5 | `brief`, `digest`, `diagram` with budget enforcement and degradation | size limits on an inflated fixture |
| M6 | README, example config, smoke test on a public multi-module Spring Boot project | manual + documented |
| M7 (v2) | `import-actuator`: beans, mappings, conditions, configprops, scheduledtasks → `source='runtime'`; effective/compare views; local-only profile docs | captured JSON fixtures |
