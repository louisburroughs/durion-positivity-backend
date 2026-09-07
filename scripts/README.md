# Durion Positivity Backend — Scripts

Utility scripts for development, operations, testing, and deployment.

---

## Script Index

| Script | Category | Purpose |
|--------|----------|---------|
| [`start-all-services.sh`](#start-all-servicessh) | Service Lifecycle | Start all services via Maven Spring Boot plugin |
| [`start-services-local.sh`](#start-services-localsh) | Service Lifecycle | Build JARs then start a local subset from disk |
| [`stop-all-services.sh`](#stop-all-servicessh) | Service Lifecycle | Stop all services tracked in `logs/*.pid` |
| [`workorder-kafka-local.sh`](#workorder-kafka-localsh) | Kafka | Manage the local Kafka stack for `pos-workorder` |
| [`check-noarg-now.sh`](#check-noarg-nowsh) | Code Quality | Detect forbidden no-arg `Instant.now()` / `LocalDateTime.now()` calls |
| [`check-flyway-hygiene.sh`](#check-flyway-hygienesh) | Code Quality | Validate Flyway migration hygiene across `pos-*` modules |
| [`check-coverage-floor-drift.sh`](#check-coverage-floor-driftsh) | Coverage | Verify each module's JaCoCo floor still sits a sane distance under its coverage |
| [`update-coverage-floors.sh`](#update-coverage-floorssh) | Coverage | Re-derive the per-module JaCoCo coverage floors and write them into the poms |
| [`verify-secrets.sh`](#verify-secretssh) | Security | Scan `application.yml` files for hardcoded secrets |
| [`verify-docker-compose-secrets.sh`](#verify-docker-compose-secretssh) | Security | Verify `docker-compose.yml` secrets are fully externalized |
| [`check-authz-doc-drift.sh`](#check-authz-doc-driftsh) | Documentation / Security | Check live authz docs against current token, endpoint, and catalog-version expectations |
| [`inventory-flyway-modules.sh`](#inventory-flyway-modulessh) | Database | Inventory Flyway-managed modules for baseline planning |
| [`emit-pos-accounting-baseline.sh`](#emit-pos-accounting-baselinesh) | Database | Emit accounting schema from a disposable Postgres container |
| [`compare-schema-tables.py`](#compare-schema-tablespy) | Database | Table-aware diff of two schema SQL files |
| [`build-pos-accounting-baseline-from-dump.py`](#build-pos-accounting-baseline-from-dumpy) | Database | Convert `pg_dump` output to a Flyway baseline SQL file |
| [`verify-observability.sh`](#verify-observabilitysh) | Observability | Check Jaeger, Prometheus, Grafana, and OTEL Collector health |
| [`generate-openapi.sh`](#generate-openapish) | API | Generate per-module and aggregate OpenAPI specs |
| [`check-openapi-inventory-drift.sh`](#check-openapi-inventory-driftsh) | API | Verify every spec-producing module is registered in `module-inventory.yaml` |
| [`check-deploy-service-drift.sh`](#check-deploy-service-driftsh) | Deployment | Verify every deployable service is registered in all five deploy lists |
| [`tests/deploy-backend-config-only-selftest.sh`](#testsdeploy-backend-config-only-selftestsh) | Deployment | Drive `deploy-backend.sh --config-only`'s image pre-flight against a stubbed Docker |
| [`tests/deploy-backend-disk-reclaim-selftest.sh`](#testsdeploy-backend-disk-reclaim-selftestsh) | Deployment | Drive `deploy-backend.sh`'s pre-pull disk reclaim against a stubbed Docker and `df` |
| [`generate-kafka-topics.py`](#generate-kafka-topicspy) | Kafka | Derive the `kafka-topic-init` topic map from the topics services configure and consume |
| [`check-kafka-topic-drift.sh`](#check-kafka-topic-driftsh) | Kafka | Verify `kafka-topic-init` provisions every topic the code uses |
| [`generate-permissions.sh`](#generate-permissionssh) | Permissions | Regenerate `permissions.yaml` files from `@PreAuthorize` annotations |
| [`export-permission-registrations-yaml.py`](#export-permission-registrations-yamlpy) | Permissions | Aggregate all `permissions.yaml` manifests into one report |
| [`redeploy-backend-tag.sh`](#redeploy-backend-tagsh) | Deployment | Update `BACKEND_TAG` and redeploy services on the alpha EC2 host |
| [`update-version.sh`](#update-versionsh) | Versioning | Bump the Maven project version (patch / minor / major) |
| [`quick-reference.sh`](#quick-referencesh) | Versioning | Print version management quick-reference to stdout |
| [`story_export.sh`](#story_exportsh) | CI / Stories | Export `story-implementation` GitHub issues to `.story-work/inbox/` |
| [`test-gateway-refactoring.sh`](#test-gateway-refactoringsh) | Testing | Integration checks for the gateway API versioning refactor |
| [`run_test.sh`](#run_testsh) | Testing | Run a focused set of `pos-accounting` tests and grep failures |
| [`rag_gap_harness.py`](#rag_gap_harnesspy) | Evaluation | RAG corpus gap-discovery harness — ask/grade/classify + hybrid flip-threshold (#1125) |
| [`rag_lock_sweep.py`](#rag_lock_sweeppy) | Evaluation | Gate 5 retrieval-lock sweep — on-disk RAG corpus vs the embedded `mcp_rag_preload_record` rows (#1217) |
| [`nlti_live_verify.py`](#nlti_live_verifypy) | Evaluation | Live HTTP verification harness for the NLTI gates — per-suite, multi-persona, Loki telemetry (#1367) |
| [`fix_uuids*.py`](#fix_uuidspy-family) | Migration | One-time UUID normalization helpers for test files |

---

## Service Lifecycle

### `start-all-services.sh`

Starts the full service stack using `mvn spring-boot:run` with the `dev` profile (H2 databases). Services are launched in dependency order: Eureka → Security → Gateway → business services.

**Usage:**
```bash
./scripts/start-all-services.sh
```

**Notes:**
- Assumes modules are already compiled. To rebuild first: `./mvnw clean compile -DskipTests`
- Writes PID files and logs to `./logs/`
- Eureka Dashboard: http://localhost:8761 | Gateway: http://localhost:8080

---

### `start-services-local.sh`

Builds all service JARs (`mvn clean package -DskipTests`) then starts a local subset — Eureka, Security, and Accounting — from the built JARs.

**Usage:**
```bash
./scripts/start-services-local.sh
```

**Notes:**
- Useful for iterating on a specific service without starting the full stack
- Writes PID files and logs to `./logs/`
- To stop services: `./scripts/stop-all-services.sh`

---

### `stop-all-services.sh`

Stops all services whose PID files exist in `./logs/`. Also sends `pkill` for any lingering `spring-boot` processes in the project.

**Usage:**
```bash
./scripts/stop-all-services.sh
```

---

## Kafka

### `workorder-kafka-local.sh`

Manages the local Kafka Docker Compose stack for the `pos-workorder` module (`pos-workorder/docker-compose.kafka.yml`).

**Usage:**
```bash
./scripts/workorder-kafka-local.sh up
./scripts/workorder-kafka-local.sh down
./scripts/workorder-kafka-local.sh restart
./scripts/workorder-kafka-local.sh logs [service]   # defaults to 'kafka'
./scripts/workorder-kafka-local.sh topics
```

**Notes:**
- Kafka bootstrap: `localhost:9092`
- Kafka UI: http://localhost:8098

---

## Code Quality

### `check-noarg-now.sh`

Scans `src/main` and `src/test` for forbidden no-arg clock calls (`Instant.now()`, `LocalDateTime.now()`). These must receive a `Clock` argument to support deterministic testing.

**Usage:**
```bash
./scripts/check-noarg-now.sh
```

Exits non-zero and prints offending lines if any are found.

---

### `check-flyway-hygiene.sh`

Validates Flyway migration hygiene rules across all `pos-*` modules.

**Usage:**
```bash
./scripts/check-flyway-hygiene.sh
```

**Checks:**
- Duplicate `V<version>__...` migration numbers within a module
- Modules with `db/migration/*.sql` but missing Flyway dependencies
- Non-test runtime configs using `ddl-auto: update` for Flyway-managed modules
- Migration filenames not matching `V<integer>__<description>.sql` or `R__<description>.sql`

---

## Security

### `verify-secrets.sh`

Scans `pos-*/src/main/resources/application.yml` for hardcoded secrets: `pos_password`, `changeit` keystore passwords, and `admin` passwords.

**Usage:**
```bash
./scripts/verify-secrets.sh
```

Prints a pass/fail summary for each check and lists services that have been migrated to environment variables.

---

### `verify-docker-compose-secrets.sh`

Checks that `docker-compose.yml` contains no hardcoded PostgreSQL, Grafana, or datasource passwords, and that all services reference `${...}` environment variables instead.

**Usage:**
```bash
./scripts/verify-docker-compose-secrets.sh
```

Also checks that `.env` contains `CHANGE_ME` placeholders and `.env.example` is safe to commit.

---

### `check-authz-doc-drift.sh`

Checks the active authorization documents against a few high-value sources of drift:

- `PermissionCode.CATALOG_VERSION` vs `GatewayPermissionCatalog.CATALOG_VERSION`
- token-guide HTTP verbs for login, validate, revoke, and token-pair examples
- stale `Required role(s):` phrasing in active docs
- stale `/api/permissions/register` examples in active docs
- presence of both `perm_bits` and `roles` in the canonical contract docs

**Usage:**
```bash
./scripts/check-authz-doc-drift.sh
```

**Notes:**
- Exits non-zero when documentation and live code disagree
- Requires the sibling `durion` repo to be present next to `durion-positivity-backend`

---

## Database / Flyway

### `inventory-flyway-modules.sh`

Inventories Flyway-managed modules and summarizes the inputs needed for a collapsed-baseline reset.

**Usage:**
```bash
./scripts/inventory-flyway-modules.sh
./scripts/inventory-flyway-modules.sh --format tsv
```

**Includes:**
- Module name and compose database name
- Versioned vs. repeatable migration counts
- First and last versioned migration file
- H2 migration layout (`split`, `nested`, `none`)
- Whether a custom `FlywayConfig` exists
- Detected extension usage (`timescaledb`, `pgvector`)
- Rough count of parity/gap-style migrations

---

### `emit-pos-accounting-baseline.sh`

Bootstraps `pos-accounting` against a disposable Postgres container with Flyway disabled and Hibernate schema creation enabled, then dumps the emitted schema to a host file for baseline curation.

**Usage:**
```bash
./scripts/emit-pos-accounting-baseline.sh
```

**Default behavior:**
- Recreates `pos_accounting_baseline_tmp` inside Docker container `pos-accounting-baseline-pg`
- Starts `pos-accounting` with the required local-only overrides
- Waits for core tables to appear
- Dumps schema to `/tmp/pos_accounting_baseline_emitted.sql`

**Useful overrides:**
- `POSTGRES_CONTAINER`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `HOST_DB_PORT`
- `SCRATCH_DB`, `EMITTED_SCHEMA_OUTPUT`, `BOOTSTRAP_LOG`

---

### `compare-schema-tables.py`

Compares two schema SQL files by table name instead of raw line order. Useful when diffing `pg_dump` output against a hand-written baseline file.

**Usage:**
```bash
./scripts/compare-schema-tables.py left.sql right.sql
./scripts/compare-schema-tables.py left.sql right.sql --summary-only
./scripts/compare-schema-tables.py left.sql right.sql --tables accounting_event vendor_bill
```

**What it does:**
- Groups `CREATE TABLE`, `ALTER TABLE`, and `CREATE INDEX` statements by table
- Normalizes whitespace and schema qualifiers
- Prints a per-table unified diff instead of a file-order diff

---

<a id="build-pos-accounting-baseline-from-dumpy"></a>

### `build-pos-accounting-baseline-from-dump.py`

Transforms emitted `pg_dump` schema output for `pos-accounting` into a Flyway-ready baseline SQL file while leaving repeatable seed/data scripts separate.

**Usage:**
```bash
./scripts/build-pos-accounting-baseline-from-dump.py
./scripts/build-pos-accounting-baseline-from-dump.py /tmp/pos_accounting_baseline_emitted.sql
./scripts/build-pos-accounting-baseline-from-dump.py input.sql output.sql
```

**Default behavior:**
- Reads `/tmp/pos_accounting_baseline_emitted.sql`
- Keeps `CREATE TABLE`, `CREATE SEQUENCE`, `CREATE INDEX`, `ALTER TABLE`, and `ALTER SEQUENCE`
- Drops `pg_dump` noise (`SET`, ownership, grants, comments, `setval`)
- Writes the cleaned result to `pos-accounting/src/main/resources/db/baseline-reset/V1__baseline_accounting_schema.sql`

---

## Observability

### `verify-observability.sh`

Checks that the full observability stack is running and accessible, then reports on active Prometheus targets and Jaeger traces.

**Usage:**
```bash
./scripts/verify-observability.sh
```

**Checks:**
- Docker Compose services: `jaeger`, `prometheus`, `grafana`, `otel-collector`
- HTTP health endpoints for each
- Active Prometheus targets count
- POS services registered in Docker Compose
- Services with traces in Jaeger
- Required config files (`application-observability.yml`, `prometheus.yml`, OTEL config)
- Dockerfiles that include the OpenTelemetry agent

**Endpoint summary (when running):**
| Service | URL |
|---------|-----|
| Grafana | http://localhost:3000 |
| Jaeger | http://localhost:16686 |
| Prometheus | http://localhost:9090 |
| OTEL Collector | http://localhost:13133 |

---

## API & Permissions

### `generate-openapi.sh`

Generates `openapi.yaml` for every configured module and creates an aggregate index spec.

**Usage:**
```bash
./scripts/generate-openapi.sh
./scripts/generate-openapi.sh pos-api-gateway pos-workorder
./scripts/generate-openapi.sh --aggregate-output docs/openapi-aggregate.yaml
./scripts/generate-openapi.sh --no-aggregate
```

**What it does:**
1. Discovers modules configured to output `openapi.yaml`
2. Runs Maven generation per module (`verify` by default)
3. Produces an aggregate index spec at `pos-api-gateway/docs/openapi-aggregate.yaml`
   - The aggregate uses `$ref` pointers to each module's `openapi.yaml`
   - Duplicate paths across modules are skipped and listed in `x-duplicate-paths-skipped`

Generated module specs are sanitized with `scripts/sanitize-openapi.py`, which
also canonicalizes mapping-key order. This prevents nondeterministic Springdoc
discovery order from producing noisy diffs; array order is preserved.

**Notes:**
- Requires `python3` and `PyYAML` for aggregate generation.
- Module generation still works with `--no-aggregate`.
- The `API Artifacts Sync` workflow (`.github/workflows/api-artifacts-sync.yml`,
  `workflow_dispatch`) runs this script in CI and then regenerates both SDKs and
  runs the frontend tests against the result. See `docs/DEVELOPMENT_GUIDE.md`
  ("Generating OpenAPI Specs", Method 3).

---

### `check-openapi-inventory-drift.sh`

Verifies that every module with a committed `openapi.yaml` is registered in
`pos-openapi-validation/src/test/resources/openapi/module-inventory.yaml`.

`OpenApiRepositoryValidator` only walks registered modules, so an unregistered module's spec is validated by nothing — it can go missing, stop parsing, or ship operations with no `summary`/`description` while the build stays green. Four modules had drifted out this way before this check existed (#1243, #1262).

**Usage:**
```bash
./scripts/check-openapi-inventory-drift.sh
```

**Checks:**
- Modules with a generated `openapi.yaml` that have no inventory entry
- Inventory entries that are not reactor modules of the root `pom.xml` (stale after a rename or removal)

**Notes:**
- Exits non-zero on either kind of drift; runs in CI alongside the other drift guards.
- Registration mode is not checked — only presence. A module that cannot be made `STRICT`-clean should be registered `REPORT_ONLY` or `EXCEPTION` rather than left out.
- `EXEMPT_MODULES` in the script exists for specs the validator cannot load at all; prefer an `EXCEPTION` entry with a reason in the inventory itself.

---

### `check-deploy-service-drift.sh`

Verifies that every deployable service is registered in all five lists a deploy depends on:
the root `docker-compose.yml` service, `ALL_SERVICES_JSON` in `.github/workflows/build-push-ecr.yml`,
the alpha image override, the alpha start order, and a Prometheus scrape job.

Nothing made those lists agree, and the same drift was fixed by hand three times (pos-supplier,
pos-marketing, and eight services missing scrape jobs). A service half-wired this way builds and
merges clean, then either never gets an image, never starts on alpha, or emits metrics nothing
collects (#1580).

**Usage:**
```bash
./scripts/check-deploy-service-drift.sh
```

**Checks:**
- Modules with a `Dockerfile` that have no compose service, and deployed modules whose `Dockerfile`
  is gone (anchoring on the `Dockerfile` means a module that loses one would otherwise drop out of
  every other check rather than fail it)
- Each of the four remaining lists, in both directions: a deployed service with no entry, and an
  entry for a service that is not deployed
- Gateway `lb://` routes pointing at a service in no deploy list (they can only 503)
- Two compose services publishing the same host port — compose accepts it, and the second
  container to start dies with "port is already allocated", taking its alpha start batch with it
- Stale `ALLOWLIST` entries — a module listed as not-deployed that is in fact wired up, or that no
  longer has a `Dockerfile`

**Notes:**
- Exits non-zero on any drift; runs in CI alongside the other drift guards.
- The universe is anchored on "has a `Dockerfile`", not "has a compose service": deploy drift happens
  at the transition from not-deployed to deployed, which the latter cannot see.

---

### `tests/deploy-backend-config-only-selftest.sh`

Drives the real `deployment/alpha/deploy-backend.sh --config-only` against a stubbed `docker`/`aws`
and a throwaway `ALPHA_ROOT`, then asserts on the compose commands it issued.

It covers the pre-flight image check, whose whole job is to decide what a config-only sync does when
an image cannot be resolved at the `BACKEND_TAG` the alpha box is pinned to — a decision that is
otherwise only observable on the box, after a merge:

- every image resolves — all tiers applied
- a service missing an image with no container on the box (a service added by the commit being
  synced) — skipped with a warning, every other tier applied
- a service missing an image that does have a container — hard failure, nothing applied

**Usage:**
```bash
bash scripts/tests/deploy-backend-config-only-selftest.sh
```

**Notes:**
- The stub reads `MISSING_IMAGES` and `EXISTING_CONTAINERS` (space-separated service names); it never
  parses the compose files, which only have to exist.
- Run it after any change to the `--config-only` branch of `deploy-backend.sh`.
- Built-but-undeployed modules carry an `ALLOWLIST` entry in the script with a status and a reason.
  Placeholder status is not durable — the stale-entry checks are what notice when it stops being true.
- The module <-> compose-service mapping is read from each service's `build.context`, so
  `pos-service-discovery` running as the compose service `eureka-server` needs no special case.

---

### `tests/deploy-backend-disk-reclaim-selftest.sh`

Drives `deploy-backend.sh`'s `reclaim_disk_before_pull` against a stubbed `docker` and `df`.

A deploy pulls roughly thirty images at a fresh `sha-` tag, and `run_periodic_docker_prune` is the
last line of the script — so it never runs on a deploy that failed. Once the box filled up mid-pull,
that turned a single failure into a permanent one: every later deploy died at the same pull, and the
cleanup that would have freed the space was unreachable without a human on the host (#1862).
`reclaim_disk_before_pull` checks headroom before pulling instead, which is what keeps that deadlock
from forming.

Cases:

- free space at or above `DOCKER_MIN_FREE_GIB` — no prune
- free space below it — prune runs and stamps the prune state file
- the prune itself fails — warned, returns 0 so the deploy continues, state file left unstamped
- `DOCKER_MIN_FREE_GIB=0` — reclaim disabled
- `DOCKER_MIN_FREE_GIB` not an integer — skipped with a message
- `df` cannot read the Docker data root — skipped, and the deploy is **not** aborted
- the shipped `DOCKER_MIN_FREE_GIB` default, above and below the floor
- `docker info` failing with the daemon down — the data root falls back to a usable path
- a non-default Docker data root — that path is the one measured and reported
- `df` failing only after the prune — warned, never a line that scans as success
- a prune that frees too little — warned rather than reported as success
- the shipped `ALPHA_ROOT` and prune-state-file defaults, with nothing injected
- an unwritable prune state file — warned, returns 0
- the call site's position in `deploy-backend.sh`, asserted statically

**Usage:**
```bash
bash scripts/tests/deploy-backend-disk-reclaim-selftest.sh
```

**Notes:**
- `deploy-backend.sh` runs a deploy top-to-bottom and cannot be sourced whole, so the self-test
  slices the three reclaim functions out of it. The slice asserts all three are present, so renaming
  or reordering them fails the test loudly instead of leaving it asserting nothing.
- The `df` stub reads `FREE_KIB`; leaving it unset makes `df` fail, which is the unreadable case.
  It also rejects a malformed path the way real `df` does, so a caller that builds a bad data root
  is caught rather than silently measured.
- Each case runs under `set -euo pipefail`, because that is what `deploy-backend.sh` runs under and
  shell options do not cross a new `bash`. Without them the harness cannot observe the failure that
  matters: a bare assignment from a failing pipeline aborting the deploy.
- The reclaim uses `docker image prune -af`, not the `docker system prune -af` the periodic prune
  runs. `system prune` removes stopped containers first, and `service_has_container` reads those to
  tell "this image was retagged out from under a live service, stop the sync" from "this service was
  never deployed here, skip it". Nothing is given up: on alpha when this wedged, containers held
  4.238 MB with 0 B reclaimable against 61.42 GB reclaimable in images. Every case asserts no
  container prune ever runs.
- The functions are driven in isolation, so nothing in them observes whether they are *called*.
  Ordering is the point of the fix, so `assert_call_site` pins it against the script text: called
  exactly once, after the guards that promise the host is untouched, before `COMPOSE_ARGS` is built
  and so before any pull in either mode.

---

### `generate-kafka-topics.py`

Derives the `kafka-topic-init` topic map in `docker-compose.yml` from the topics services actually
configure and consume, and rewrites the block between the generated-topics markers.

The list used to be hand-written and had drifted to 14 entries against ~35 configured topics. Topics
absent from it are created implicitly by the broker at Kafka defaults — 1 partition,
`cleanup.policy=delete`, 7-day retention. For events and commands that matches the intent, but 30
DLQs were running at 7 days instead of 30, on exactly the topics whose contents are meant to survive
long enough for a human to investigate (#1578, #1579).

**Usage:**
```bash
./scripts/generate-kafka-topics.py            # print the generated block
./scripts/generate-kafka-topics.py --apply    # rewrite docker-compose.yml
./scripts/generate-kafka-topics.py --check    # exit 1 with a diff if the block is stale
./scripts/generate-kafka-topics.py --list     # print "<topic> <retention-ms>" lines
```

**Sources:**
- `@KafkaListener(topics = ...)` defaults across `pos-*/src/main/java` (consumed), with
  `${property}` placeholders resolved against the module's `application.yml` when the annotation
  carries no inline default
- `*topic` property defaults in `pos-*/src/main/resources/application.yml`,
  `DomainTopics.events/commands/manifest("domain")` calls, and `*_TOPIC = "..."` constants (produced)
- `<topic>.dlq` for every consumed topic — the DLQ set is not declared anywhere, it is implied by
  `record.topic() + ".dlq"` in the twelve `KafkaErrorHandlingConfig` classes (ADR-0044 §4)

A `DomainTopics` call taking a constant (`DomainTopics.events(ORDER_DOMAIN)`) is resolved against
the same file — the names are not globally unique, `OWNER` being `"supplier"` in one pos-catalog
class and `"inventory"` in another. A reference that cannot be resolved to a literal is a hard
error, never a silent omission: dropping one quietly reproduces the gap this script exists to
close.

**Notes:**
- Retention follows the suffix: `.dlq` 30d, `.manifest.v1` 3d, everything else 7d.
- Run it after adding a listener or a `*-topic` property, and commit `docker-compose.yml`.

---

### `check-kafka-topic-drift.sh`

CI entry point for `generate-kafka-topics.py --check`. Fails when the committed topic block in
`docker-compose.yml` no longer matches what the code implies.

`kafka-topic-init` is not a local-dev convenience: `deployment/alpha/deploy-backend.sh` runs it in
both the full and the config-only deploy mode, so its list is the config every topic actually gets on
alpha.

**Usage:**
```bash
./scripts/check-kafka-topic-drift.sh
```

**Notes:**
- Fix a failure with `./scripts/generate-kafka-topics.py --apply` and commit `docker-compose.yml`.

---

### `check-coverage-floor-drift.sh`

Verifies that every module's coverage ratchet still gates something.

Root `pom.xml` checks each module against `<jacoco.line.min>` / `<jacoco.branch.min>`, set a few points below measured coverage (`docs/TEST_COVERAGE_IMPROVEMENT_PLAN.md` §6.2). Nothing kept those floors in step with the code: `jacoco:check` catches a module falling below its floor, but not a floor left far behind a module that improved. When this check was written the reactor's floors permitted roughly 3,400 covered lines and 970 covered branches to disappear — about four points of overall coverage — without one build turning red.

**Usage:**
```bash
# Measure the way both binding gates measure, then check.
./mvnw -pl pos-coverage-aggregate -am verify -DskipITs -Darchunit.skipTests=true -T 1C
./scripts/check-coverage-floor-drift.sh
```

**Fails on:**
- `BREACH` — measured coverage is under the floor (`jacoco:check` fails too; this names the module and counter)
- `THIN` — the cushion is under `--min-cushion` (default 2). §6.2: "a cushion any thinner than about two points is not a gate, it is a coin toss"
- `STALE` — the cushion is over `--max-cushion` (default 6), i.e. coverage rose and the floor was never raised behind it
- `UNGUARDED` — a module with at least `--min-lines` lines (default 50) carries no floor

**Notes:**
- Reads each module's own `target/site/jacoco/jacoco.csv`, never the aggregate — the aggregate credits a shared library with its consumers' coverage, and it is not what `jacoco:check` evaluates (§6.1).
- Refuses to score coverage produced with ITs. Failsafe inherits the JaCoCo agent through `@{argLine}` and appends to the same `jacoco.exec`, so an IT-inclusive run yields floors the gate can never reproduce. `--allow-its` overrides, at the cost of that guarantee.
- Modules with no `jacoco.csv` are listed and skipped, not failed — only a build that ran tests produces one.
- Runs nightly in the `Full Coverage SonarCloud Analysis` job, right after the ratchet itself.
- Fix any finding with `./scripts/update-coverage-floors.sh --apply`.

---

### `update-coverage-floors.sh`

Re-derives every module's JaCoCo floors from the last `-DskipITs` build and writes them into the module poms. This is what makes the ratchet a ratchet — §6.2 says "raise a module's floor when its coverage rises", and before this script nothing did.

**Usage:**
```bash
./mvnw -pl pos-coverage-aggregate -am verify -DskipITs -Darchunit.skipTests=true -T 1C
./scripts/update-coverage-floors.sh            # preview the changes
./scripts/update-coverage-floors.sh --apply    # write the poms
```

**Options:**
- `--cushion N` — points below measured coverage (default 3, per §6.2)
- `--allow-lower` — permit lowering a floor; §6.2 requires the reason in the commit message
- `--allow-its` — proceed despite Failsafe reports, accepting floors the gate cannot reproduce

**Notes:**
- Floors are raised, never lowered: a proposal below the standing floor is reported and dropped unless `--allow-lower`.
- Never writes a `0.00` floor — "a 0.00 floor is not a gate" (§6.2); an all-zero module needs first tests, not a threshold.
- Refreshes the `Coverage ratchet: measured …` comment in each pom it touches, so the recorded numbers and cushion stay honest.
- Adds the properties (and a `<properties>` block, in POM-sequence position) to a module that has none.
- Both scripts share `coverage_floors.py`; its tests are `scripts/tests/test_coverage_floors.py`.

---

### `generate-permissions.sh`

Regenerates `src/main/resources/permissions.yaml` for each module by statically scanning `@PreAuthorize` annotations in Java source for `hasAuthority` and `hasAnyAuthority` calls. **Additive-only** — it never removes existing entries, since some permissions may be enforced via programmatic authority checks that are invisible to static analysis.

Also called automatically at the end of each `generate-openapi.sh` run (pass `--no-permissions` to that script to skip it).

**Usage:**
```bash
./scripts/generate-permissions.sh                          # regenerate all modules
./scripts/generate-permissions.sh pos-workorder pos-accounting  # specific modules only
./scripts/generate-permissions.sh --dry-run                # print changes without writing
./scripts/generate-permissions.sh --check                  # exit non-zero if any file would change (CI)
```

**What it does:**
1. Discovers all modules with a `src/main/resources/permissions.yaml`
2. Scans `src/main/java/**/*.java` for `@PreAuthorize` annotations (handles multi-line)
3. Extracts permission strings from `hasAuthority('x')` and `hasAnyAuthority('x', 'y', ...)`
4. Filters out `ROLE_*` strings and permissions from other domains (cross-domain refs are logged as warnings)
5. Merges newly discovered permissions into the existing YAML, preserving hand-written descriptions
6. Sorts all entries alphabetically and writes the file
7. Writes the aggregate permissions report to `docs/permissions-report.yaml`

**Notes:**
- Requires `python3` and `PyYAML`.
- `--check` is suitable for CI to detect YAML drift after controller changes.
- The aggregate report is only refreshed on normal write runs; `--dry-run` and `--check` skip it.
- **Does not update `CATALOG_VERSION`.** Catalog version bumps are a separate manual step — see [Adding a New Permission](../../../durion/docs/architecture/AUTHORIZATION_MODEL.md#adding-a-new-permission) in `AUTHORIZATION_MODEL.md`.

---

### `export-permission-registrations-yaml.py`

Walks the repository for `permissions.yaml` manifests and aggregates them into a single YAML report with a flat permissions list, per-module breakdown, duplicate detection, and parse-error summary.

**Usage:**
```bash
./scripts/export-permission-registrations-yaml.py
./scripts/export-permission-registrations-yaml.py -o docs/permissions-report.yaml
./scripts/export-permission-registrations-yaml.py --strict        # exit 1 on duplicates or parse errors
./scripts/export-permission-registrations-yaml.py --root /path/to/root
```

**Output includes:**
- `generatedAt`, `root`, `summary` (counts)
- `manifests` — per-module breakdown with permission list
- `permissions` — flat sorted list with domain, serviceName, and source path
- `duplicates` — permission names that appear in more than one module
- `parseIssues` — manifests that failed to parse

**Notes:**
- Works with or without `PyYAML` installed (falls back to a line-by-line parser).

---

## Deployment

### `redeploy-backend-tag.sh`

Updates `BACKEND_TAG` in `/opt/durion/alpha/.env`, reconciles the shared `postgres` service if its compose image changed, then runs `docker compose pull` and `docker compose up -d --force-recreate` using the alpha compose files.

**Usage:**
```bash
./scripts/redeploy-backend-tag.sh <backend-tag> [service...]
```

**Examples:**
```bash
# Redeploy one service with a new backend image tag
./scripts/redeploy-backend-tag.sh sha-a20f156 pos-vehicle-inventory

# Short commit form (script prepends "sha-" automatically)
./scripts/redeploy-backend-tag.sh a20f156 pos-security-service pos-api-gateway

# Redeploy all services with the new backend tag
./scripts/redeploy-backend-tag.sh sha-a20f156
```

**Notes:**
- Run this on the EC2 host where `/opt/durion/alpha` exists.
- Env overrides: `ALPHA_ROOT`, `BACKEND_DIR`, `ENV_FILE`, `PROD_OVERRIDE`, `LOG_TAIL`.
- If the deployed `postgres` container lags behind the merged compose config, the script automatically pulls and recreates `postgres` before the app rollout.

---

## Version Management

### `update-version.sh`

Automated semantic versioning for the multi-module Maven project.

**Usage:**
```bash
./scripts/update-version.sh [patch|minor|major] [--commit]
```

**Examples:**
```bash
./scripts/update-version.sh patch              # Preview 0.1.0 → 0.1.1-SNAPSHOT
./scripts/update-version.sh minor --commit     # Bump minor and auto-commit
./scripts/update-version.sh major --commit     # Bump major and auto-commit
```

**What it does:**
1. Extracts current version from root `pom.xml`
2. Calculates new version based on semver rules
3. Updates all module `pom.xml` files via the Maven Versions Plugin
4. Displays a diff preview
5. Optionally commits with `--commit`

---

### `quick-reference.sh`

Prints a concise version management quick-reference to stdout. Useful as a one-line reminder without opening the full README.

**Usage:**
```bash
./scripts/quick-reference.sh
```

---

## CI / Stories

### `story_export.sh`

Exports all GitHub issues labelled `story-implementation` from the backend repository to `.story-work/inbox/<number>.json`.

**Usage:**
```bash
./scripts/story_export.sh
```

**Notes:**
- Requires `gh` CLI authenticated to the repo.
- Output directory is `.story-work/inbox/` relative to the repo root.

---

### `test-gateway-refactoring.sh`

Integration test suite that verifies the gateway API versioning refactoring is complete and correct.

**Usage:**
```bash
./scripts/test-gateway-refactoring.sh
```

**Checks:**
1. `spring.application.name` updated correctly in all 17 services
2. `pos-api-gateway` module compiles
3. Sample services (`pos-inventory`, `pos-order`, `pos-catalog`, `pos-security-service`) compile
4. `ApiVersionHeaderToPathFilter` exists and has the required methods/constants
5. Gateway discovery locator config and lower-case service ID enabled
6. Gateway dev profile configuration (port 8080, segregated management port)

---

### `run_test.sh`

Convenience script to run the three primary `pos-accounting` integration/unit tests and grep for failures.

**Usage:**
```bash
./scripts/run_test.sh
```

Output is written to `./mvn_test_output.log` and the failure block is printed to stdout.

---

## Evaluation

### `rag_gap_harness.py`

RAG corpus gap-discovery harness (#1125). Asks realistic questions through the MCP chat API,
reproduces the retrieval that fed the prompt (reusing `eval_live.py`'s DB path), grades answers with
a source-grounded judge, classifies every failure into a four-way taxonomy (corpus gap / retrieval
miss / generation / permission gating), and emits a human-actionable gap report — plus the
dense-vs-hybrid recovery evidence for #1124's `mcp.rag.hybrid.lexical-enabled` flip-threshold.

**Usage:**
```bash
pip install --user pg8000
POS_MCP_TOKEN_ROLE_USER=... scripts/run-gap-harness.sh --judge ollama --recall-at-k 0.85
python3 scripts/rag_gap_harness.py replay --results pos-mcp-server/target/gap-harness/results.json
python3 scripts/rag_gap_harness.py calibrate --judge ollama
```

Pure decision logic lives in the `scripts/gap_harness/` package and is unit-tested
(`scripts/tests/test_gap_harness.py`); `replay`, `emit-fixture`, and `calibrate` on a set carrying
`predicted_verdict` run offline. Full docs: [`scripts/gap_harness/README.md`](gap_harness/README.md). Design:
`pos-mcp-server/docs/rag-corpus-gap-harness-design.md`.

---

### `nlti_live_verify.py`

Live HTTP verification harness for the NLTI phase gates (#1367). Where `eval_live.py` drives
Postgres/pgvector directly, this one exercises the real `pos-mcp-server` HTTP surface through
`pos-api-gateway` as N configured personas, harvests the `nlti.request.telemetry` stream from Loki
via LogQL, and emits both a JSON result file and a paste-ready markdown evidence block shaped like
the gate blocks in `pos-mcp-server/docs/implementation_checklist.md`.

Suites (`--suite a,b` is repeatable; `--suite all` runs everything): `equivalence` (Gate 2A /
#1214), `persona` (Gate 1 / #1213), `workflow` (Gate 2C / #1215), `router` (Gate 4 / #1216),
`write-gate` (Gate 6 / #1218), `admin` (Gate 7 / #1219).

**Usage:**
```bash
# offline: print the exact request plan, open no sockets, exit 0
python3 scripts/nlti_live_verify.py --suite all --dry-run \
    --personas scripts/fixtures/nlti-personas.example.json

# live: Wave 1 of the gate close-out plan (#1213 + #1214)
NLTI_PERSONA_OPS_ADMIN_PASSWORD=... NLTI_PERSONA_TECHNICIAN_PASSWORD=... \
    python3 scripts/nlti_live_verify.py --suite equivalence,persona \
    --gateway-url http://localhost:8080 --loki-url http://localhost:3100 \
    --personas /opt/durion/alpha/nlti-personas.json

# live: non-IDLE workflow activation (#1215)
python3 scripts/nlti_live_verify.py --suite workflow --workflow-state PROCESSING_RETURN ...

# live: the write-gate flow needs an explicit, opted-in write target (#1218, open decision C)
python3 scripts/nlti_live_verify.py --suite write-gate --allow-writes \
    --write-target /opt/durion/alpha/nlti-write-target.json ...
```

**Notes:**
- Stdlib only — no `pip install` needed. YAML persona files work only if PyYAML happens to be
  present; JSON always works.
- Personas are configured in a JSON/YAML file that names **env vars**, never secrets — see
  [`scripts/fixtures/nlti-personas.example.json`](fixtures/nlti-personas.example.json). Token
  resolution: `$<tokenEnv>` → `$POS_MCP_TOKEN_<role>` → env file → gateway login.
- Safety: the default mode never mutates alpha business data. `POST /v1/nlt/requests/{id}/confirm`
  and the admin CRUD writes are skipped unless `--allow-writes` is passed, and the `write-gate`
  suite additionally requires `--write-target` (there is deliberately no default). `--read-only`
  restricts the run to GET/auth requests only.
- Telemetry is harvested from Loki (`--loki-url`, `observability/loki-config.yml`), never from
  `docker compose logs`. Every request — the chat endpoints included — is sent with a generated
  `X-Correlation-Id` and joined primarily by correlationId (the server-side correlation filter
  echoes the header on all endpoints). Against a server predating that filter the harness falls
  back to the legacy time-window + actor-role join and downgrades every dependent check to
  `UNVERIFIED-ATTRIBUTION` (renders unchecked, holds the gate decision, never flips the exit
  code): the 2026-08-19 live run proved the window join can attribute the previous persona's
  record.
- The persona suite asserts the fixture's `expectsPermissions`/`lacksPermissions` for real: the
  telemetry actor block carries only `permissionCodeCount` (never the code list), so each declared
  code is verified by probing the endpoint class it gates — non-401/403 proves a held code, 403
  proves an absent one. `mcp:tool:manage` uses a no-op revoke of a nonexistent permission code so
  the probe cannot mutate.
- The write-gate ACTION probe defaults to a delete-verb prompt (`--write-gate-action-prompt`)
  because HIGH-risk prompts exercise the strictest gate invariants. The always-on
  `wg-intent-gap` check submits a create-style prompt and asserts the #1398 contract
  (create/update phrasings classify as ACTION and reach the write gate): with a
  `--write-target` clientContext it must yield a write-plan preview (`planId`), without one it
  must yield `NEEDS_CLARIFICATION` (missing `targetTool`) — the plain ACCEPTED envelope of the
  old UNKNOWN dead-end (#1218 product gap) is a real FAIL.
- Exit codes: `0` all executed checks passed (or `--dry-run`), `1` a check failed, `2` configuration
  error, `3` infrastructure error (auth/gateway/Loki unreachable).
- Plan: `pos-mcp-server/docs/gate-closeout-plan-1212-1219.md` (Wave 0.3).

---

### `rag_lock_sweep.py`

Gate 5 retrieval-lock sweep (#1217) — the live half. Hashes every static RAG document declared under
`mcp.rag.preload.docs` and compares it against the `mcp_rag_preload_record` rows the service wrote
when it embedded that corpus, so drift between the shipped corpus and what is actually in the
database is caught. It also cross-checks that each document has chunk rows (with non-NULL vectors) in
`mcp_document_embedding` and reports documents that exist in the DB but have left the manifest.

The hash comparison is the point: `StaticRagPreloadServiceImpl` skips re-embedding when the newest
`LOADED` row already carries the same hash, so a corpus edit that never reached the running service
looks healthy from the application side and is only visible by hashing the files on disk.

The offline half of the same lock — deterministic/unique id, `rag-scope`, an explicit permission
decision, resolvable `source-path`, documented chunking — is
`pos-mcp-server/src/test/java/com/positivity/mcp/eval/RetrievalLockTest.java`, which runs in the
normal `pos-mcp-server` surefire build.

**Usage:**
```bash
# offline: print the plan and every on-disk hash, open no DB connection, exit 0
python3 scripts/rag_lock_sweep.py --dry-run

# live: Wave 1 step 2 of the gate close-out plan (#1217)
pip install --user pg8000
ENV_FILE=/opt/durion/alpha/.env python3 scripts/rag_lock_sweep.py
```

**Notes:**
- Strictly read-only: every statement goes through a `SELECT`-only guard and the session is opened
  `READ ONLY`. Re-seeding is a separate tool (`scripts/rag_seed.py`).
- The manifest defaults to `application-alpha.yml` because `StaticRagPreloadServiceImpl` is
  `@Profile("alpha")` and a profile list replaces the base list; `--config` overrides it. PyYAML is
  used when installed, otherwise a stdlib parser reads the preload block.
- Emits a paste-ready markdown evidence block plus JSON under
  `pos-mcp-server/target/rag-lock-sweep/` (`--markdown-out` / `--json-out` to override).
- Exit codes: `0` every document locked (or `--dry-run`), `1` drift detected, `2` configuration
  error, `3` infrastructure error (driver missing, DB unreachable) — non-zero on drift so it can be
  wired into CI.
- Plan: `pos-mcp-server/docs/gate-closeout-plan-1212-1219.md` (Wave 0.2, run in Wave 1 step 2).

---

## Migration Utilities

### `fix_uuids*.py` family

One-time migration helpers used to normalize hardcoded `UUID.fromString` literals in `pos-accounting` test files. These are historical scripts and are not needed for regular development.

| Script | What it did |
|--------|------------|
| `fix_uuids.py` | Reformatted 32-digit UUID strings to dashed form |
| `fix_fixed_uuids.py` | Replaced repeated `00000000-0000-0000-0000-000000000001` with incrementing hex suffixes |
| `fix_user_uuids.py` | Variant of the above for user UUID constants |
| `fix_uuids2.py` | Iterative refinement pass on the same test files |
| `fix_uuids_properly.py` | Final pass — injected `nextUuid()` helper and replaced all fixed UUIDs with calls to it |

---

## Version Management Workflow

### Standard Development Flow

```bash
# 1. Work on features in -SNAPSHOT version

# 2. When ready to release, bump version
./scripts/update-version.sh minor --commit

# 3. Create release (without -SNAPSHOT)
./mvnw release:prepare release:perform
```

### Semantic Versioning Rules

| Type | When to use | Example |
|------|-------------|---------|
| Patch | Bug fixes | 0.1.0 → 0.1.1 |
| Minor | New backwards-compatible features | 0.1.0 → 0.2.0 |
| Major | Breaking changes | 0.2.0 → 1.0.0 |

### Release Workflow

```bash
./scripts/update-version.sh minor --commit
git tag v0.2.0
git push origin main --tags
```

## Manual Version Commands

```bash
# Check current version
./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout

# Set specific version
./mvnw versions:set -DnewVersion=0.2.0-SNAPSHOT -DprocessAllModules

# Review changes
git diff pom.xml **/pom.xml

# Commit if satisfied
git add pom.xml **/pom.xml
git commit -m "chore: bump version to 0.2.0-SNAPSHOT"

# Undo version changes
git checkout pom.xml **/pom.xml
```

## Configuration

The Maven Versions Plugin is configured in the root `pom.xml`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-versions-plugin</artifactId>
    <version>2.16.2</version>
    <configuration>
        <generateBackupPoms>false</generateBackupPoms>
    </configuration>
</plugin>
```

## Additional Resources

- `docs/VERSION_MANAGEMENT.md` — comprehensive version guide (if present)
- [Maven Versions Plugin](https://www.mojohaus.org/versions/versions-maven-plugin/)
- [Semantic Versioning](https://semver.org/)
