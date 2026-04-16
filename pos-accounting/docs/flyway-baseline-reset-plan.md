# pos-accounting Flyway Baseline Reset Plan

## Objective

Replace the current `pos-accounting` versioned migration chain with a single
collapsed baseline for disposable environments such as `alpha`.

Current active state:

- `V1__baseline_accounting_schema.sql`
- keep `R__seed_reference_accounting.sql`
- archive the legacy `V1..V15` chain outside `db/migration`

Target outcome:

- remove the need for parity/type-fix migrations on brand-new databases
- rebuild disposable environments from the collapsed baseline plus repeatable
  seed data

Active baseline path:

- `src/main/resources/db/migration/V1__baseline_accounting_schema.sql`

Legacy chain archive path:

- `src/main/resources/db/archive/accounting-pre-baseline-reset/`

## Baseline Source

The active `V1` now comes directly from emitted Postgres schema output generated
from the current entity model, then cleaned into Flyway-ready SQL. Repeatable
seed/data remains separate in `R__seed_reference_accounting.sql`.

## Database

- Compose database: `pos_accounting_db`
- Service: `pos-accounting`

## Current Versioned Chain To Fold Into New V1

### Functional schema migrations

- `V1__create_bill_number_sequence.sql`
- `V2__create_default_gl_mapping_table.sql`
- `V3__create_statement_line_mappings.sql`
- `V4__create_event_outbox_table.sql`
- `V5__add_payment_outcome_tables.sql`
- `V6__add_accounting_status_columns.sql`
- `V7__add_discrepancy_columns.sql`
- `V8__create_accounting_status_sync_audit.sql`

### Parity/bootstrap migrations

- `V9__create_accounting_additional_parity_tables.sql`
- `V10__tighten_accounting_precision_parity.sql`
- `V11__close_accounting_precision_gap_examples.sql`
- `V12__add_accounting_remaining_entity_columns.sql`

### Type-correction migrations that should disappear into the new baseline

- `V13__convert_accounting_audit_log_entity_id_to_uuid.sql`
- `V14__convert_accounting_event_final_posting_reference_id_to_varchar.sql`
- `V15__convert_accounting_event_mapping_version_attempted_to_varchar.sql`

## Repeatables To Keep Separate

- `R__seed_reference_accounting.sql`

This should stay repeatable unless we decide the seed data is immutable enough
to collapse into the baseline. Default recommendation: keep it separate.

## Critical Final Types To Preserve In The New V1

### `accounting_audit_log`

- `entity_id UUID`

### `accounting_event`

- `final_posting_reference_id VARCHAR(100)`
- `mapping_version_attempted VARCHAR(50)`

These types reflect the current entity model and the post-fix chain. They must
be represented directly in the new baseline so we do not reintroduce V13-V15 as
follow-up fixes.

## Baseline Generation Workflow

### One-command helper

If you already have a Docker Postgres container running, use:

```bash
./scripts/emit-pos-accounting-baseline.sh
```

Defaults:

- container: `pos-accounting-baseline-pg`
- scratch DB: `pos_accounting_baseline_tmp`
- emitted schema file: `/tmp/pos_accounting_baseline_emitted.sql`
- bootstrap log: `/tmp/pos_accounting_baseline_bootstrap.log`

The helper recreates the scratch DB, starts `pos-accounting` with the required
local-only overrides, waits for core tables, then dumps the emitted schema.

Then turn that emitted dump directly into the candidate Flyway baseline:

```bash
python3 ./scripts/build-pos-accounting-baseline-from-dump.py
```

This is now the active pilot workflow for `pos-accounting`: treat the emitted
Postgres schema as the baseline source of truth, and keep
`R__seed_reference_accounting.sql` separate.

### Manual flow

1. Create scratch DB:

```bash
createdb pos_accounting_baseline_tmp
```

2. Run `pos-accounting` against the scratch DB with Flyway disabled and schema
   creation enabled:

```bash
cd pos-accounting

../mvnw spring-boot:run \
  -Dspring-boot.run.arguments="\
    --spring.datasource.url=jdbc:postgresql://localhost:5432/pos_accounting_baseline_tmp \
    --spring.datasource.username=$POSTGRES_USER \
    --spring.datasource.password=$POSTGRES_PASSWORD \
    --spring.flyway.enabled=false \
    --spring.jpa.hibernate.ddl-auto=create"
```

3. Dump schema:

```bash
pg_dump \
  --schema-only \
  --no-owner \
  --no-privileges \
  --dbname=postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5432/pos_accounting_baseline_tmp \
  > /tmp/pos_accounting_baseline_candidate.sql
```

4. Convert `/tmp/pos_accounting_baseline_emitted.sql` into
   `V1__baseline_accounting_schema.sql`.

```bash
python3 ./scripts/build-pos-accounting-baseline-from-dump.py \
  /tmp/pos_accounting_baseline_emitted.sql \
  pos-accounting/src/main/resources/db/migration/V1__baseline_accounting_schema.sql
```

## Verification Checklist

The new baseline is ready when all are true:

- empty `pos_accounting_db`
- Flyway creates schema from the collapsed `V1`
- `R__seed_reference_accounting.sql` applies cleanly
- `pos-accounting` starts with `ddl-auto=validate`
- no `missing column`, `missing table`, or `wrong column type` Hibernate errors
- the emitted-schema baseline does not require additional parity/type-fix
  migrations on a brand-new database

## Alpha Cutover Commands

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

## Notes

- The old chain is now archived outside Flyway's active scan path.
- Treat `pos-accounting` as the pilot for the broader module-by-module reset
  process.
