# codemap

Explore the architecture of a Spring Boot application spread over many Maven repos together
with **any chat LLM**, including one that has no tools, no plugins and no repository access.

codemap never calls an LLM. It turns your compiled code into a SQLite fact database, gives the
chat a compact brief and an application map to paste, and then runs the SQL the LLM asks for.
You stay in the loop: copy the LLM's answer, run `codemap run`, paste the result back.

```
 repos (mvn compile) ──scan──▶ codemap.db ──brief/digest──▶ chat LLM
                                   ▲                            │ answer ends with ```sql / ```cmd blocks
                                   └──── codemap run ◀── copy ──┘
                                         (results on the clipboard: paste them back)
```

## Requirements

- Java 21 to run codemap. The scanned code may target any Java version ASM reads (up to 25).
- The repos must be compiled (`mvn compile` or `mvn package`): codemap reads `target/classes`.
- Clipboard support: WSL/Windows (PowerShell + `clip.exe`), macOS (`pbcopy`), Linux
  (`wl-clipboard`, `xclip` or `xsel`). Files and stdin work everywhere.

## Build

```bash
mvn package            # runs the tests, produces target/codemap.jar
alias codemap='java -jar /path/to/codemap/target/codemap.jar'
```

## Quick start

```bash
# 1. Scan. Pass a folder holding the repos (each a dir with pom.xml), or the repos themselves.
codemap scan ~/src/shop --app shop --db shop.db

# 2. Start a chat: paste the brief, then the digest.
codemap brief  --db shop.db --copy
codemap digest --db shop.db --app shop --copy

# 3. Ask your question in the chat. The LLM ends its answer with ```sql / ```cmd blocks.
#    Copy the whole answer, then:
codemap run --db shop.db          # reads the clipboard, writes results to the clipboard
#    Paste the results back into the chat. Repeat.
```

Rescans are incremental: a repo is skipped when its git commit and build output are unchanged
(repos with uncommitted changes are always rescanned).

## Commands

| Command | What it does |
|---|---|
| `scan DIR... --app NAME [--db F] [--force] [--config F] [--classpath-from-maven]` | Scan repos into the database. |
| `brief [--db F] [--copy\|--out F]` | Rules for the LLM + schema with an example query per table (≤ 15k chars). |
| `digest --app NAME [--db F] [--max-chars N] [--copy\|--out F]` | Application map (≤ 80k chars; summarised by package/class when larger). |
| `run [FILE\|-] [--db F] [--page N] [--max-chars N] [--stdout\|--out F]` | Run the blocks of an LLM answer; output ≤ 80k chars with explicit `TRUNCATED:` lines. |
| `diagram --repo X [--kind components\|artifacts]` / `diagram --app NAME` | C4-PlantUML diagrams (built-in PlantUML C4 library). |
| `import-actuator --service S [--db F] DIR` | Load actuator JSON dumps of a running service as runtime facts. |

Inside `run`, ```` ```cmd ```` blocks accept one command per line:

```
source com.example.shop.orders.app.DefaultOrderService#place
source OrderController
diagram orders
diagram shop-common artifacts
diagram app
```

`--page 2` re-runs the previous answer and shows the rows after those shown on page 1.

## What is extracted

| Area | Facts |
|---|---|
| Maven | modules, coordinates (inherited groupId/version, properties, dependencyManagement), dependencies; dependencies on modules of other scanned repos are linked (`module_links`). |
| Code | classes, supertypes (transitive, through dependency jars), annotations with attributes and **meta-annotations** (a custom annotation carrying `@Service` counts as a service), fields, methods with readable generic signatures and line ranges, call edges with line numbers (lambdas folded into their method), class dependencies. |
| Roles | controller, service, repository, component, configuration, entity, client, listener, application; plus your own rules. |
| Spring | services (`@SpringBootApplication`), beans (component scan, `@Bean`, Spring Data, `AutoConfiguration.imports`), injection points and their resolution (qualifier, `@Primary`, `@Profile`, name), `@Value` expressions. |
| Entry points | HTTP endpoints (class + method mappings, placeholders and context path resolved), Kafka/JMS/Rabbit listeners (destinations resolved from yml), `@EventListener`, `@Scheduled`. |
| Outbound | `@HttpExchange` and Feign clients, RestClient/WebClient/RestTemplate calls, Kafka/JMS/Rabbit/StreamBridge sends (topic from constants or `@Value` fields), published events. |
| Flows | `message_flows` (producer → topic → consumer) and `http_flows` (client → endpoint, matched by path and method). |
| JPA | entities and tables, relations, repositories → entity. |
| Config | `application*.yml/properties` flattened per profile; `config_effective` overlays a profile on the defaults. |

Every Spring fact table has a `source` column (`static` | `runtime`): runtime facts imported
from actuator sit next to the static ones (see below).

## Runtime facts (optional)

When you can run a service locally, capture its actuator `beans`, `mappings`, `conditions`,
`configprops` and `scheduledtasks` endpoints and import them:

```bash
codemap import-actuator --service orders --db shop.db captures/orders
```

The `*_effective` views then prefer the observed wiring, mappings and schedules for that
service; `beans_compare` and `endpoints_compare` show what static analysis missed. The brief and
digest mention it. See [`docs/runtime-facts.md`](docs/runtime-facts.md) for a local-only profile.

## Local settings

Project-specific rules go in `codemap.local.yml` (read from the working directory, or
`--config`). Keep it out of version control. See [`codemap.example.yml`](codemap.example.yml):

```yaml
roles:
  - annotation: com.example.platform.UseCase   # meta-annotations are followed
    role: service
  - extends: com.example.platform.AbstractMessageHandler
    role: listener
  - package: "*.adapter.out.*"
    role: client
calls:
  include: [com.example.platform.messaging.EventBus]   # keep calls to this API
```

### Annotations from dependency jars

Meta-annotations and supertypes of classes **outside** the scanned repos (for example custom
stereotypes defined in a shared framework jar) are read from each module's
`target/codemap.classpath`. Create these files with:

```bash
codemap scan ~/src/shop --app shop --classpath-from-maven
# or yourself, per repo:
mvn -q dependency:build-classpath -Dmdep.outputFile=target/codemap.classpath
```

Without them, codemap still knows the standard Spring stereotypes and mapping annotations.

## Limits of static analysis

- Beans created by library auto-configuration, and conditions evaluated at startup, are not
  visible. Injection points satisfied by them show `candidates = 0`.
- URLs and topics built at runtime (string concatenation, values from a database) are not
  resolved; `target_expr` keeps what was found.
- Placeholders resolve with **no profile active**; values that differ per profile are listed in
  `scan_warnings` (`profile_dependent`) and in `config_effective`.
- `source` excerpts rely on line numbers from the bytecode (`-g`, the Maven default).

## Database

SQLite, one file. Natural keys only (class = fqn, method = `fqn#name(ParamSimpleTypes)`), so
the LLM never joins on surrogate ids. `codemap brief` prints the full schema; `schema_doc`
holds it in the database. Design notes: [`docs/design.md`](docs/design.md).

## Roadmap

- Endpoint and listener annotations from in-house frameworks declared in `codemap.local.yml`.
- Optional exact source ranges with a Java parser if the bytecode-based heuristic falls short.
