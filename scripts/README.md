# Durion Positivity Backend - Scripts

Utility scripts for managing the project build, versioning, and deployment.

## Available Scripts

### `check-flyway-hygiene.sh` - Flyway Migration Guardrails

Validates migration hygiene rules across `pos-*` modules.

**Usage:**
```bash
./check-flyway-hygiene.sh
```

**Checks:**
- Duplicate `V<version>__...` migration numbers in a module
- Modules with `db/migration/*.sql` but missing Flyway dependencies
- Non-test runtime configs using `ddl-auto: update` for Flyway-managed modules
- Migration filenames not matching `V<integer>__<description>.sql` or `R__<description>.sql`

### `inventory-flyway-modules.sh` - Baseline Reset Inventory

Inventories Flyway-managed modules and summarizes the inputs needed for a
collapsed-baseline reset.

**Usage:**
```bash
./inventory-flyway-modules.sh
./inventory-flyway-modules.sh --format tsv
```

**Includes:**
- module name
- compose database name
- versioned vs repeatable migration counts
- first and last versioned migration file
- H2 migration layout (`split`, `nested`, `none`)
- whether a custom `FlywayConfig` exists
- detected extension usage (`timescaledb`, `pgvector`)
- rough count of parity/gap-style migrations

### `emit-pos-accounting-baseline.sh` - Scratch Schema Emitter

Bootstraps `pos-accounting` against a disposable Postgres database with Flyway
disabled and Hibernate schema creation enabled, then dumps the emitted schema to
a host file for baseline curation.

**Usage:**
```bash
./emit-pos-accounting-baseline.sh
```

**Default behavior:**
- recreates `pos_accounting_baseline_tmp` inside Docker container `pos-accounting-baseline-pg`
- starts `pos-accounting` with the required local-only overrides
- waits for core tables to appear
- dumps schema to `/tmp/pos_accounting_baseline_emitted.sql`

**Useful overrides:**
- `POSTGRES_CONTAINER`
- `POSTGRES_USER`
- `POSTGRES_PASSWORD`
- `HOST_DB_PORT`
- `SCRATCH_DB`
- `EMITTED_SCHEMA_OUTPUT`
- `BOOTSTRAP_LOG`

### `compare-schema-tables.py` - Table-Aware Schema Diff

Compares two schema SQL files by table name instead of raw line order.
Useful when diffing `pg_dump` output against a hand-written baseline file.

**Usage:**
```bash
./compare-schema-tables.py left.sql right.sql
./compare-schema-tables.py left.sql right.sql --summary-only
./compare-schema-tables.py left.sql right.sql --tables accounting_event vendor_bill
```

**What it does:**
- groups `CREATE TABLE`, `ALTER TABLE`, and `CREATE INDEX` statements by table
- normalizes whitespace and schema qualifiers
- prints a per-table unified diff instead of a file-order diff

### `build-pos-accounting-baseline-from-dump.py` - Dump To Flyway Baseline

Transforms emitted `pg_dump` schema output for `pos-accounting` into a
Flyway-ready baseline SQL file while leaving repeatable seed/data scripts
separate.

**Usage:**
```bash
./build-pos-accounting-baseline-from-dump.py
./build-pos-accounting-baseline-from-dump.py /tmp/pos_accounting_baseline_emitted.sql
./build-pos-accounting-baseline-from-dump.py input.sql output.sql
```

**Default behavior:**
- reads `/tmp/pos_accounting_baseline_emitted.sql`
- keeps `CREATE TABLE`, `CREATE SEQUENCE`, `CREATE INDEX`, `ALTER TABLE`, and `ALTER SEQUENCE`
- drops `pg_dump` noise such as `SET`, ownership, grants, comments, and `setval`
- writes the cleaned result to
  `pos-accounting/src/main/resources/db/baseline-reset/V1__baseline_accounting_schema.sql`

**Temporary rollout note:**
- `ddl-auto: update` currently emits a warning by default (non-blocking).
- Set `ENFORCE_DDL_AUTO_UPDATE_CHECK=true` to make it fail the script again.

### `redeploy-backend-tag.sh` - Update Tag + Redeploy Services on EC2

Updates `BACKEND_TAG` in `/opt/durion/alpha/.env`, reconciles the shared `postgres`
service if its compose image changed, then runs `docker compose pull` and
`docker compose up -d --force-recreate` using the alpha compose files.

**Usage:**
```bash
./redeploy-backend-tag.sh <backend-tag> [service...]
```

**Examples:**
```bash
# Redeploy one service with a new backend image tag
./redeploy-backend-tag.sh sha-a20f156 pos-vehicle-inventory

# Same, but pass short commit form (script adds "sha-")
./redeploy-backend-tag.sh a20f156 pos-security-service pos-api-gateway

# Redeploy all services with the new backend tag
./redeploy-backend-tag.sh sha-a20f156
```

**Notes:**
- Run this on the EC2 host where `/opt/durion/alpha` exists.
- Supports optional env overrides: `ALPHA_ROOT`, `BACKEND_DIR`, `ENV_FILE`, `PROD_OVERRIDE`, `LOG_TAIL`.
- If the deployed `postgres` container is still on an older image than the merged compose
  config, the script automatically pulls and recreates `postgres` before the app rollout.

### `generate-openapi.sh` - Per-Module + Aggregate OpenAPI Generation

Generates `openapi.yaml` for every configured module and then creates an aggregate index spec.

**Usage:**
```bash
./generate-openapi.sh [options] [module...]
```

**Examples:**

```bash
# Generate for all configured modules + aggregate file
./generate-openapi.sh

# Generate only selected modules + aggregate file
./generate-openapi.sh pos-api-gateway pos-workorder

# Generate and write aggregate file to a custom location
./generate-openapi.sh --aggregate-output docs/openapi-aggregate.yaml

# Generate module specs only (skip aggregate)
./generate-openapi.sh --no-aggregate
```

**What it does:**
1. Discovers modules configured to output `openapi.yaml`
2. Runs Maven generation per module (`verify` by default)
3. Produces aggregate index spec at `pos-api-gateway/docs/openapi-aggregate.yaml` by default
    - The aggregate file uses `$ref` pointers to each module's `openapi.yaml`
    - Duplicate path keys across modules are skipped and listed in `x-duplicate-paths-skipped`

**Notes:**
- Requires `python3` and `PyYAML` for aggregate generation.
- Module generation still works if aggregate generation is disabled via `--no-aggregate`.

### `update-version.sh` - Semantic Version Management

Automated semantic versioning for the multi-module Maven project.

**Usage:**
```bash
./update-version.sh [patch|minor|major] [--commit]
```

**Examples:**

```bash
# Preview patch bump (0.1.0 → 0.1.1-SNAPSHOT)
./update-version.sh patch

# Bump minor version and auto-commit
./update-version.sh minor --commit

# Bump major version and auto-commit
./update-version.sh major --commit
```

**What it does:**
1. Extracts current version from root `pom.xml`
2. Calculates new version based on semantic versioning rules
3. Updates ALL 27 module `pom.xml` files using Maven Versions Plugin
4. Displays preview of changes
5. Optionally commits changes (with `--commit` flag)

**Key Features:**
- ✅ Safe preview mode (no --commit = no changes written)
- ✅ Updates all 27 modules automatically
- ✅ Semantic versioning support (major/minor/patch)
- ✅ Clear git workflow instructions
- ✅ Smart version extraction (ignores Spring Boot parent version)

**Output Example:**
```
📦 Current version: 0.1.0
🚀 Updating to version: 0.2.0-SNAPSHOT

⏳ Updating all pom.xml files...
✅ Version updated successfully

Changed files:
pom.xml
pos-accounting/pom.xml
pos-agent-framework/pom.xml
... (27 modules total)

Preview of changes:
diff --git a/pom.xml b/pom.xml
-       <version>0.1.0-SNAPSHOT</version>
+       <version>0.2.0-SNAPSHOT</version>
```

## Version Management Workflow

### Standard Development Flow

```bash
# 1. Work on features in -SNAPSHOT version
#    (currently 0.1.0-SNAPSHOT)

# 2. When ready to release, bump version
./update-version.sh minor --commit

# 3. Create release (without -SNAPSHOT)
./mvnw release:prepare release:perform
```

### Semantic Versioning Rules

| Type  | When to use | Example |
|-------|-----------|---------|
| Patch | Bug fixes | 0.1.0 → 0.1.1 |
| Minor | New features (backwards-compatible) | 0.1.0 → 0.2.0 |
| Major | Breaking changes | 0.2.0 → 1.0.0 |

### Release Workflow

```bash
# 1. Bump version to next development cycle
./update-version.sh minor --commit

# 2. Create git tag for the release
git tag v0.2.0

# 3. Push to remote
git push origin main --tags
```

## Manual Version Commands

If you prefer not to use the script:

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

## Troubleshooting

### Script Permission Error

```bash
chmod +x scripts/update-version.sh
```

### Maven Not Found

The script uses `./mvnw` (Maven Wrapper). If it's not available:
```bash
# Use system Maven if available
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DprocessAllModules
```

### Wrong Version Detected

The script intelligently skips the Spring Boot parent version (4.0.1) and reads the actual project version. If it's still wrong, check that `pom.xml` has the correct version element after the `<artifactId>positivity</artifactId>` tag.

### Undo Version Changes

```bash
git checkout pom.xml **/pom.xml
```

## Additional Resources

- See [VERSION_MANAGEMENT.md](../docs/VERSION_MANAGEMENT.md) for comprehensive guide
- [Maven Versions Plugin](https://www.mojohaus.org/versions/versions-maven-plugin/)
- [Semantic Versioning](https://semver.org/)
