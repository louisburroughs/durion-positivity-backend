# Flyway Baseline Reset Runbook

## Purpose

Use this runbook when a service database is disposable and the current Flyway
chain has accumulated too many parity-fix migrations to be worth preserving.

This is the recommended `alpha` strategy when:

- application data can be discarded
- `flyway_schema_history` does not need to be preserved
- the current module is failing on many missing columns, missing tables, or type
  mismatches
- the migration chain has drifted away from the current entity model

This is **not** the right production strategy.

## Goal State

For each module database, end up with:

- one collapsed `V1__baseline_<module>_schema.sql`
- optional `V2+` versioned migrations only for real post-baseline changes
- optional `R__...` repeatable seed scripts for reference data
- application startup that passes with `spring.jpa.hibernate.ddl-auto=validate`

## Preconditions

1. Freeze schema-changing work for the pilot modules while rebuilding their
   baseline.
2. Confirm the target databases are disposable.
3. Confirm the module can be pointed at a scratch PostgreSQL database.
4. Inventory the current modules first:

```bash
./scripts/inventory-flyway-modules.sh
```

## Recommended Order

Start with the worst drift offenders first:

1. `pos-accounting`
2. `pos-customer`
3. `pos-inventory`
4. `pos-security-service`
5. `pos-workorder`

After those are stable, continue module by module.

## Per-Module Workflow

### 1. Capture Current Chain

Record:

- database name
- current versioned migration files
- repeatable migrations
- extensions required (`pgvector`, `timescaledb`, etc.)
- custom Flyway config or H2 split migration paths

For `pos-accounting`, see
[pos-accounting/docs/flyway-baseline-reset-plan.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-accounting/docs/flyway-baseline-reset-plan.md).

### 2. Create Scratch Database

Create a temporary empty PostgreSQL database for schema generation.

Example:

```bash
createdb pos_accounting_baseline_tmp
```

### 3. Generate Candidate Schema From Current Code

Point the module at the scratch database and start it with:

- Flyway disabled
- Hibernate DDL create mode enabled

Example shape:

```bash
./mvnw -pl pos-accounting -am spring-boot:run \
  -Dspring-boot.run.arguments="\
    --spring.datasource.url=jdbc:postgresql://localhost:5432/pos_accounting_baseline_tmp \
    --spring.datasource.username=$POSTGRES_USER \
    --spring.datasource.password=$POSTGRES_PASSWORD \
    --spring.flyway.enabled=false \
    --spring.jpa.hibernate.ddl-auto=create"
```

After startup creates the schema, dump the schema only:

```bash
pg_dump \
  --schema-only \
  --no-owner \
  --no-privileges \
  --dbname=postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5432/pos_accounting_baseline_tmp \
  > /tmp/pos_accounting_baseline_candidate.sql
```

### 4. Curate the New V1

Do not use the raw dump as-is. Fold back in:

- required `CREATE EXTENSION` statements
- custom indexes not represented by JPA
- sequence setup
- non-entity-owned tables
- JSON/JSONB/UUID precision choices that matter to runtime
- comments or manual DDL required for PostgreSQL compatibility

Write the new baseline as:

```text
src/main/resources/db/migration/V1__baseline_<module>_schema.sql
```

### 5. Keep or Rewrite Repeatables

Repeatable `R__...` scripts should stay separate if they represent:

- reference data
- seed data
- idempotent catalogs or manifests

Do not collapse those into `V1` unless they are truly immutable bootstrap data.

### 6. Archive the Old Chain

Once the collapsed `V1` is ready, archive the old `V2+...Vn` chain for that
module.

Recommended archive layout:

```text
src/main/resources/db/archive/pre-reset/
```

Keep:

- the new `V1`
- any retained `R__...` repeatables
- special H2 migrations in their separate H2 path

### 7. Prove Fresh Bootstrap

For each rebuilt module:

1. empty database
2. Flyway migrate
3. application startup with `ddl-auto=validate`
4. no Hibernate schema validation errors

Required checks:

```bash
./mvnw -q -pl <module> -am -DskipTests package
```

Run the module against a brand-new database and verify startup succeeds.

## Alpha Cutover

Once the new baseline image is built and deployed, recreate the affected
database entirely. Dropping the database also drops `flyway_schema_history`.

Example shape:

```bash
cd /opt/durion/alpha/backend

sudo docker compose -f docker-compose.yml -f /opt/durion/alpha/docker-compose.prod.yml \
  --env-file /opt/durion/alpha/.env exec postgres sh -lc \
  'psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE IF EXISTS pos_accounting_db WITH (FORCE);"'

sudo docker compose -f docker-compose.yml -f /opt/durion/alpha/docker-compose.prod.yml \
  --env-file /opt/durion/alpha/.env exec postgres sh -lc \
  'psql -U "$POSTGRES_USER" -d postgres -c "CREATE DATABASE pos_accounting_db;"'

sudo docker compose -f docker-compose.yml -f /opt/durion/alpha/docker-compose.prod.yml \
  --env-file /opt/durion/alpha/.env up -d --force-recreate pos-accounting
```

Repeat for each rebuilt module database.

## Post-Cutover Verification

Use bulk log inspection to catch remaining startup mismatches:

```bash
cd /opt/durion/alpha/backend

for s in $(sudo docker compose -f docker-compose.yml -f /opt/durion/alpha/docker-compose.prod.yml \
  --env-file /opt/durion/alpha/.env ps --services); do
  echo "=== $s"
  sudo docker compose -f docker-compose.yml -f /opt/durion/alpha/docker-compose.prod.yml \
    --env-file /opt/durion/alpha/.env logs --no-color --tail=200 "$s" \
    | grep -E "Schema validation:|wrong column type encountered|missing column|missing table|FlywayException|Validate failed" || true
done
```

## Exit Criteria

A module is considered reconciled when:

- it boots from an empty database using the collapsed `V1`
- Flyway applies cleanly
- repeatables seed cleanly
- Hibernate validate passes
- alpha startup shows no schema-related errors for that module
