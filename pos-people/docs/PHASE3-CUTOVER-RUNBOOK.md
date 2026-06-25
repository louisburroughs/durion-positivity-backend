# pos-people Baseline Cutover Runbook (Phase 3: drop DB + redeploy)

Collapsing the Flyway chain into a single `V1` baseline changes the V1 checksum and removes
V2..V10. Flyway will **not** reconcile that against an existing `flyway_schema_history` — the
existing DB must be reset so the new baseline runs from empty. This runbook does that safely.

> Pre-production only. There is no data to preserve on alpha beyond seed/demo data, which the
> repeatable seeds recreate. Still: **take a backup first**.

---

## 0. Preconditions

- [ ] Branch `refactor/pos-people-baseline-cleanup` merged to `main`; CI built a new pos-people image.
- [ ] **API contract change shipped**: `user_person_link` endpoints moved `userId → username`
      (`/v1/people/users/{username}/link|person`, `/v1/people/{personId}/users` returns usernames).
      OpenAPI regenerated, Angular SDK (`@durion-sdk/people`) regenerated + vendored, and any
      frontend user-link UI updated to the new method names
      (`getPersonByUsername`, `getUsernamesByPersonId`, `username` DTO field). Deploy these together.
- [ ] Maintenance window: pos-people will be briefly unavailable; CRM person names/contacts degrade
      to null while it is down (by design — pos-customer sources identity from pos-people).

---

## 1. GATE — confirm you are on the pos-people database

pos-people has its **own database/service** with its own `flyway_schema_history` (one history per
db/service). So the reset is self-contained: nothing else lives in this DB. The only gate is making
sure you are pointed at the right database before dropping.

### Connect to the Postgres container

```sh
# 1. Find the pos-people Postgres container (compose deploys show a 'pos-people' / 'postgres' name).
docker ps --format '{{.Names}}\t{{.Image}}' | grep -iE 'postgres|people'

# 2. Open a psql shell in that container. Use the DB role + db name from the deploy env
#    (SPRING_DATASOURCE_URL / POSTGRES_USER / POSTGRES_DB). Common form:
docker exec -it <pg-container> psql -U pos_user -d positivity
#    (-it = interactive; password via PGPASSWORD env on the container, or add -W to be prompted.)

# Non-interactive one-off (run a single statement / .sql file):
docker exec -i <pg-container> psql -U pos_user -d positivity -v ON_ERROR_STOP=1 -c "SELECT current_database();"
docker exec -i <pg-container> psql -U pos_user -d positivity -v ON_ERROR_STOP=1 < reset.sql
```

If Postgres is a managed instance (RDS) rather than a container, connect with `psql "$PEOPLE_DATABASE_URL"`
instead of `docker exec`. Everything below is the same once you have a psql session.

From the deploy's `SPRING_DATASOURCE_URL`, connect to the pos-people DB and confirm:

```sql
SELECT current_database();          -- the pos-people database, not another service's
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' ORDER BY table_name;   -- only pos-people tables + flyway_schema_history
```

- [ ] Connected to the pos-people DB (own history confirmed; no other service's tables present).

---

## 2. Backup

```sh
# Containerized Postgres: dump inside the container, then copy out.
TS=$(date -u +%Y%m%dT%H%M%SZ)
docker exec <pg-container> pg_dump -U pos_user -d positivity --no-owner --format=custom \
  -f /tmp/pos-people-preclear-$TS.dump
docker cp <pg-container>:/tmp/pos-people-preclear-$TS.dump ./pos-people-preclear-$TS.dump

# Managed/RDS: pg_dump "$PEOPLE_DATABASE_URL" --no-owner -Fc -f pos-people-preclear-$TS.dump
```
- [ ] Backup file exists and is non-empty.

---

## 3. Stop / scale down pos-people

Scale the pos-people deployment to 0 replicas (or stop the container) so nothing writes during the
reset and no half-migrated instance is serving.

- [ ] pos-people replicas = 0 (gateway returns 503 for `/api/people/*` — expected).

---

## 4. Reset the schema

Dropping `public` removes the old tables (incl. the pre-collapse `person_location_assignment`),
all data, and `flyway_schema_history` in one shot, so the new `V1` runs from empty. Safe because the
DB is pos-people's alone.

```sh
docker exec -i <pg-container> psql -U pos_user -d positivity -v ON_ERROR_STOP=1 <<'SQL'
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO pos_user;   -- adjust to the pos-people DB role
GRANT ALL ON SCHEMA public TO public;
SQL
```

`gen_random_uuid()` is built into Postgres 13+ (pgcore) — no extension needed. If your cluster is
older and relied on pgcrypto, re-create it after the schema reset:
`CREATE EXTENSION IF NOT EXISTS pgcrypto;`

- [ ] Schema recreated and empty (`\dt` shows no tables).
- [ ] `SELECT gen_random_uuid();` resolves.

---

## 5. Redeploy

Scale pos-people back up on the **new image** (collapsed baseline).

On startup Flyway runs `V1__baseline_people_schema.sql` then the repeatable seeds
(`R__seed_reference_people`, `R__seed_people_operational_data`, `R__seed_timekeeping_approval_data`).
Hibernate `ddl-auto=validate` then checks the entities against the fresh schema.

- [ ] pos-people pods healthy; `GET /api/people/actuator/health` → 200.
- [ ] No Flyway error in logs; `flyway_schema_history` has exactly one row: version 1, success=true,
      plus the three repeatables.

---

## 6. Verify

```sql
SELECT count(*) FROM person;                       -- ~110
SELECT count(*) FROM employee;                      -- 40
SELECT count(*) FROM user_person_links;             -- 17 (keyed by username)
SELECT count(*) FROM employee_location_assignment;  -- up to 40 (location-guarded rows depend on
                                                    --  pos-location being seeded)
SELECT count(*) FROM person_contact_point;          -- 150
-- contact_info_json is gone (derived @Formula), not a column:
SELECT count(*) FROM information_schema.columns
 WHERE table_name='person' AND column_name IN ('employee_number','status','contact_info_json'); -- 0
```

App-level:
- [ ] `GET /api/people/v1/people?q=<name>` returns names + email/phone (people search).
- [ ] CRM `/app/crm/customers` shows person names + primary contact again (pos-people identity restored).
- [ ] Login as a seeded user (e.g. `marcus.webb`) resolves their person via the username link.

The employee_location_assignment + user_person_link seeds are guarded on cross-service rows
(`location`); if pos-location is not yet seeded, those guarded rows are skipped and the repeatable
re-applies once locations exist (idempotent). Confirm staff appear in their locations after
pos-location is up.

---

## 7. Rollback

If startup fails or verification fails:
1. Scale pos-people to 0.
2. Restore the backup:
   ```sh
   pg_restore --clean --if-exists --no-owner -d "$PEOPLE_DATABASE_URL" pos-people-preclear-*.dump
   ```
3. Redeploy the **previous** image.

(The corrected baseline + seeds were validated on Postgres 16 via `FlywayMigrationIT` and a manual
container run — two idempotent passes — so a clean fresh DB is the low-risk path; rollback is the
safety net for environment-specific surprises.)

---

## Appendix A — table-scoped drop (alternative to dropping `public`)

Same effect without recreating the schema (e.g. if you prefer not to touch schema grants). Includes
both old (pre-collapse) and new table names; CASCADE clears the FKs.

```sql
DROP TABLE IF EXISTS
  employee_location_assignment, person_location_assignment,
  employee_offboarding_retry_queue,
  user_person_links, person_contact_point, employee, person,
  work_session_break, work_session,
  time_entry_adjustment, time_entry_audit, time_entry_exception, time_entry,
  timekeeping_entry, timekeeping_policy, time_period,
  flyway_schema_history
CASCADE;
```
Safe here because this is pos-people's own database (one Flyway history per db/service).
