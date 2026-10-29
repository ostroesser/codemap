# codemap

codemap explores the architecture of Spring Boot codebases (one application spread over many
Maven repos) so you can study it with any chat LLM, including one that has no tools and no repo
access.

codemap never calls an LLM. It:
1. scans compiled bytecode, POMs and `application*.yml` into a SQLite fact database,
2. emits paste-ready text: a **brief** (rules + schema for the LLM, ≤ 15k chars) and an
   **application digest** (≤ 80k chars),
3. runs the `sql` / `cmd` blocks the LLM writes and formats the results for pasting back
   (`codemap run`, clipboard in → clipboard out).

## Stack
Java 21, Maven, single fat jar. picocli, ASM, maven-model, SnakeYAML, sqlite-jdbc, JUnit 5.

## Conventions
- Design: `docs/design.md` (schema, brief, milestones).
- Tests build a synthetic multi-repo fixture (neutral domains, `com.example.*` packages).
  Keep all examples, fixtures and docs generic.
- Every table holding Spring facts (beans, injections, endpoints, listeners, config) has a
  `source` column (`static` | `runtime`).
- Project-specific settings (custom stereotypes, role mappings) live in `codemap.local.yml`,
  which is git-ignored. Never commit one; ship `codemap.example.yml` instead.
- Output budgets are hard limits: the brief must stay ≤ 15k chars, the digest and each `run`
  result ≤ 80k chars.
- `CLAUDE.local.md` (git-ignored) may hold extra local instructions; follow it.
