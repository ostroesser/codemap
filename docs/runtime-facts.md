# Runtime facts from actuator

Static analysis cannot see what Spring decides at startup: beans from auto-configuration,
which implementation is really injected, which conditions matched, the effective configuration.
A running service can tell you, through Spring Boot actuator. codemap imports five endpoints:

| Endpoint | Imported into | Gives |
|---|---|---|
| `/actuator/beans` | `beans`, `injections` | every bean with its class, origin and the beans it depends on |
| `/actuator/mappings` | `endpoints` | every request mapping actually registered |
| `/actuator/conditions` | `conditions` | auto-configuration outcomes (matched / not matched, why) |
| `/actuator/configprops` | `config_properties` (profile `(runtime)`) | bound `@ConfigurationProperties` values, keys in kebab-case |
| `/actuator/scheduledtasks` | `listeners` (kind `scheduled`) | registered schedules with resolved expressions |

All rows get `source = 'runtime'`. The `*_effective` views then use them instead of the static
rows for that service, and `beans_compare` / `endpoints_compare` show what differs.

## 1. Expose the endpoints in a local-only profile

Add a profile file to the service, used only on your machine (keep it out of deployments,
for example by not committing it or by excluding it from packaging):

```yaml
# src/main/resources/application-codemap.yml
management:
  endpoints:
    web:
      exposure:
        include: beans, mappings, conditions, configprops, scheduledtasks
  endpoint:
    configprops:
      show-values: always      # local only: values are sanitised by default
  server:
    address: 127.0.0.1         # listen on loopback only
```

The service needs `spring-boot-starter-actuator` on its classpath. Start it with the profile,
for example `mvn spring-boot:run -Dspring-boot.run.profiles=codemap` (add the profiles you
normally use locally, e.g. `dev,codemap`). Secrets in `configprops` stay masked (`******`) for
keys Spring considers sensitive; review the dump before sharing it anyway.

## 2. Capture

```bash
mkdir -p captures/orders
for e in beans mappings conditions configprops scheduledtasks; do
  curl -sf "http://127.0.0.1:8080/actuator/$e" -o "captures/orders/$e.json" || echo "no $e"
done
```

Missing files are fine: each file present replaces that service's previous runtime rows of
the same kind, and the others are left alone. (`captures/` is git-ignored.)

## 3. Import

```bash
codemap import-actuator --service orders --db shop.db captures/orders
codemap brief  --db shop.db    # now mentions the runtime data
codemap digest --app shop --db shop.db   # adds a "Runtime facts" section
```

The service name must match the `services` table (its `spring.application.name`).
Rescanning the code keeps runtime rows; re-import after the code changes.

## What to ask the LLM next

- "Which beans exist at runtime but not in the static scan, and where do they come from?"
  (`beans_compare` + `beans.declared_by = 'auto_config'`)
- "Which implementation of X is really injected into Y?" (`injections_effective`)
- "Why is the Rabbit auto-configuration off?" (`conditions`)
- "What is the effective Kafka configuration?" (`config_effective WHERE source = 'runtime'`)
