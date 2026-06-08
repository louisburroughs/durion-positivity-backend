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
| [`verify-secrets.sh`](#verify-secretssh) | Security | Scan `application.yml` files for hardcoded secrets |
| [`verify-docker-compose-secrets.sh`](#verify-docker-compose-secretssh) | Security | Verify `docker-compose.yml` secrets are fully externalized |
| [`check-authz-doc-drift.sh`](#check-authz-doc-driftsh) | Documentation / Security | Check live authz docs against current token, endpoint, and catalog-version expectations |
| [`inventory-flyway-modules.sh`](#inventory-flyway-modulessh) | Database | Inventory Flyway-managed modules for baseline planning |
| [`emit-pos-accounting-baseline.sh`](#emit-pos-accounting-baselinesh) | Database | Emit accounting schema from a disposable Postgres container |
| [`compare-schema-tables.py`](#compare-schema-tablespy) | Database | Table-aware diff of two schema SQL files |
| [`build-pos-accounting-baseline-from-dump.py`](#build-pos-accounting-baseline-from-dumpy) | Database | Convert `pg_dump` output to a Flyway baseline SQL file |
| [`verify-observability.sh`](#verify-observabilitysh) | Observability | Check Jaeger, Prometheus, Grafana, and OTEL Collector health |
| [`generate-openapi.sh`](#generate-openapish) | API | Generate per-module and aggregate OpenAPI specs |
| [`generate-permissions.sh`](#generate-permissionssh) | Permissions | Regenerate `permissions.yaml` files from `@PreAuthorize` annotations |
| [`export-permission-registrations-yaml.py`](#export-permission-registrations-yamlpy) | Permissions | Aggregate all `permissions.yaml` manifests into one report |
| [`redeploy-backend-tag.sh`](#redeploy-backend-tagsh) | Deployment | Update `BACKEND_TAG` and redeploy services on the alpha EC2 host |
| [`update-version.sh`](#update-versionsh) | Versioning | Bump the Maven project version (patch / minor / major) |
| [`quick-reference.sh`](#quick-referencesh) | Versioning | Print version management quick-reference to stdout |
| [`story_export.sh`](#story_exportsh) | CI / Stories | Export `story-implementation` GitHub issues to `.story-work/inbox/` |
| [`test-gateway-refactoring.sh`](#test-gateway-refactoringsh) | Testing | Integration checks for the gateway API versioning refactor |
| [`run_test.sh`](#run_testsh) | Testing | Run a focused set of `pos-accounting` tests and grep failures |
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

**Notes:**
- Requires `python3` and `PyYAML` for aggregate generation.
- Module generation still works with `--no-aggregate`.

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

**Notes:**
- Requires `python3` and `PyYAML`.
- `--check` is suitable for CI to detect YAML drift after controller changes.
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
