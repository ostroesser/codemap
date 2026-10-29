# Smoke test: spring-petclinic-microservices

A manual end-to-end check against a public multi-module Spring Boot 4 project.

```bash
git clone --depth 1 https://github.com/spring-petclinic/spring-petclinic-microservices.git
(cd spring-petclinic-microservices && mvn -q -B -DskipTests compile)

codemap scan spring-petclinic-microservices --app petclinic --db petclinic.db --classpath-from-maven
codemap brief  --db petclinic.db | wc -c          # must be <= 15000
codemap digest --app petclinic --db petclinic.db  # check the sections below
```

## Expected

| Check | Expected |
|---|---|
| services | 8: admin-server, api-gateway, customers-service, discovery-server, genai-service, config server, vets-service, visits-service |
| entry points | 16 HTTP endpoints (customers 8, visits 3, gateway 3, vets 1, genai 1) and 1 `ApplicationStartedEvent` listener |
| JPA | 6 entities with relations (Owner → Pet one-to-many, Vet ↔ Specialty many-to-many, ...) and 4 repositories |
| HTTP flows | 6 client calls, each matched to its endpoint: gateway → customers / visits (WebClient, URLs from string concatenation and field initializers), genai → customers / vets (RestClient) |
| injections | gateway controller → `CustomersServiceClient`, `VisitsServiceClient` resolved; circuit-breaker factory unresolved (auto-configured) |
| rescan | second `codemap scan` reports the repo as unchanged |

Reference run on a low-end 4-core ARM machine with 4 GB RAM (`-Xmx256m`): `mvn compile` about 70 s; first scan with
`--classpath-from-maven` about 45 s; full rescan about 12 s; unchanged rescan about 2.5 s.
