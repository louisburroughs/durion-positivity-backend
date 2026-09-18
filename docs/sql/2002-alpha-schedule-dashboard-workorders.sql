-- Alpha data repair for #2002 — make the daily dispatch board non-empty.
--
-- Alpha holds non-terminal workorders at one repair-capable location, every one of them with
-- scheduled_date NULL. The board selects its roster on the date, so it renders empty at every
-- location and date and the daily dispatch workflow (#60) cannot be demonstrated.
--
-- Nothing in pos-workorder ever wrote scheduled_date before this change, which is why every alpha
-- row is null: there is no endpoint that sets it and the inbound assignment fact does not carry it.
-- As of #2002 there is exactly one supported writer — placing a workorder on a bay or mobile unit —
-- so the repair splits in two, and only the half with no supported path is SQL.
--
--
-- STEP 1 (API, preferred): place the fixtures through the service.
-- ---------------------------------------------------------------------------
-- PUT /v1/workorders/{workorderId}/position does the whole job properly: it validates the resource
-- against the location replicas, enforces the one-open-workorder rule, writes the
-- service_position_assignment history row, bumps the aggregate version and publishes the
-- workorder.workorder.updated snapshot fact that pos-shop-manager's ext_workorder replica feeds on
-- — and, as of this change, supplies scheduled_date. Two bays and one mobile unit:
--
--   curl -sS -X PUT "$GATEWAY/workorder/v1/workorders/$WORKORDER_ID/position" \
--     -H "X-API-Version: 1" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
--     -d '{"resourceType":"BAY","resourceId":"<bay-uuid>","reason":"Alpha dispatch-board fixture (#2002)"}'
--
-- Requires workorder:position:assign (#2059; formerly workorder:operationalContext:override). Repeat with resourceType MOBILE_UNIT for the
-- mobile-unit fixture. Re-running is safe: re-asserting a position a workorder already holds is a
-- no-op placement that still repairs a missing date.
--
-- Do NOT place fixtures by UPDATEing workorder.resource_id here. That skips the occupancy check,
-- the history row and the fact, leaving every event-fed replica of these workorders stale — the
-- shop dashboard and the owner dashboard would then disagree until some later mutation happened to
-- republish them.
--
--
-- STEP 2 (SQL, below): date the open workorders that are not being placed.
-- ---------------------------------------------------------------------------
-- The board also needs unassigned open work on the roster, and for that there is no supported
-- writer at all — hence this script. It is the narrowest thing that does the job: one UPDATE of
-- scheduled_date, scoped to one location, on open workorders that have no date.
--
-- Two consequences the operator should know about, neither of which a guard can remove:
--
--   * No fact is published, so a consumer's ext_workorder replica keeps the null scheduled_date it
--     already holds until that workorder is next mutated through the service. Nothing downstream
--     reads scheduled_date off the replica today, and the dispatch board reads this module's own
--     table, so the board is correct immediately; the replica converges on the next real mutation.
--   * version is incremented so the row's aggregate version still moves. A consumer that later
--     receives a fact for this workorder sees a version above the one it holds, as it must, and a
--     service instance holding a stale entity loses its optimistic-lock check rather than silently
--     overwriting this repair. Prefer running it while the service is quiet all the same.
--
-- Safe to re-run: it only touches rows whose scheduled_date is still NULL.
--
--
-- Running it. Postgres runs under docker-compose, so reach psql through the container rather than
-- expecting a client on the host. First find the location — this needs no arguments:
--
--   docker compose exec -T postgres \
--     psql -U "$POSTGRES_USER" -d pos_workorder_db -c \
--     "SELECT location_id, count(*) AS workorders,
--             count(*) FILTER (WHERE scheduled_date IS NULL) AS unscheduled
--      FROM public.workorder GROUP BY location_id ORDER BY 2 DESC"
--
-- then run the repair against the location that has the work:
--
--   docker compose exec -T postgres \
--     psql -U "$POSTGRES_USER" -d pos_workorder_db \
--     -v tenant_id="'<tenant-uuid>'" -v location_id="'<location-uuid>'" \
--     < docs/sql/2002-alpha-schedule-dashboard-workorders.sql
--
-- or by container name, which is what `docker ps` shows (docker-compose.yml pins it):
--
--   docker exec -i postgres-positivity \
--     psql -U "$POSTGRES_USER" -d pos_workorder_db \
--     -v tenant_id="'<tenant-uuid>'" -v location_id="'<location-uuid>'" \
--     < docs/sql/2002-alpha-schedule-dashboard-workorders.sql
--
-- `-T` / `-i` matter: without them the script never reaches psql's stdin. Run it from the repo root
-- so the relative path resolves, and keep the inner quotes on both variables — psql substitutes
-- them literally, so a bare UUID would be parsed as an identifier rather than a string.
--
-- With a psql client on the host instead, the equivalent is:
--
--   psql "$POS_WORKORDER_URL" -v tenant_id="'<tenant-uuid>'" -v location_id="'<location-uuid>'" \
--        -f docs/sql/2002-alpha-schedule-dashboard-workorders.sql
--
-- The tenant must be bound before the first statement (ADR-0062): every table below is under row
-- level security keyed on tenant_id, so an unbound session sees nothing and updates nothing. The
-- alpha default tenant is the value of pos.tenancy.default-tenant-id.
--
-- location_id is required rather than defaulted. Dating "every undated open workorder in the
-- tenant" is a different and much larger act than dating one shop's board, and once alpha carries
-- work at a second location a re-run of the unscoped form would quietly sweep it onto today's
-- board.

\set ON_ERROR_STOP on

\if :{?location_id}
\else
\echo 'ERROR: location_id is required. Pass -v location_id="''<location-uuid>''" (see the header).'
\quit
\endif

\if :{?tenant_id}
\else
\echo 'ERROR: tenant_id is required. Pass -v tenant_id="''<tenant-uuid>''" (see the header).'
\quit
\endif

BEGIN;

SELECT set_config('app.current_tenant', :tenant_id, true);

-- ---------------------------------------------------------------------------
-- Before: what the board at this location is working from.
-- ---------------------------------------------------------------------------
SELECT 'before' AS phase,
       count(*)                                                            AS workorders,
       count(*) FILTER (WHERE scheduled_date IS NULL)                      AS unscheduled,
       count(*) FILTER (WHERE scheduled_date = CURRENT_DATE)               AS on_todays_board,
       count(*) FILTER (WHERE resource_id IS NOT NULL)                     AS placed
FROM public.workorder
WHERE location_id = :location_id;

-- ---------------------------------------------------------------------------
-- Date every open, undated workorder at this location.
-- ---------------------------------------------------------------------------
-- "Open" mirrors Workorder.isLocked(): CANCELLED is closed, COMPLETED is closed unless the
-- workorder was reopened, and a NULL status is open (it is the DRAFT the builder defaults to).
-- Closed workorders are deliberately left undated — back-dating finished work would put it on a
-- board it was never on.
--
-- version is bumped with the same UPDATE. It is the aggregate version the fact envelope carries
-- (#1486) and the JPA optimistic-lock counter, so leaving it alone would let a service instance
-- holding a stale copy of one of these rows write over the repair without its save failing.
UPDATE public.workorder
SET scheduled_date = CURRENT_DATE,
    version        = version + 1,
    updated_at     = now()
WHERE location_id = :location_id
  AND scheduled_date IS NULL
  AND (status IS NULL OR status <> 'CANCELLED')
  AND (status IS NULL OR status <> 'COMPLETED' OR is_reopened IS TRUE);

-- ---------------------------------------------------------------------------
-- After: what the board will render.
-- ---------------------------------------------------------------------------
-- placed_today should be non-zero once step 1 has run; unassigned_today is the roster half this
-- script is responsible for.
SELECT 'after' AS phase,
       count(*) FILTER (WHERE scheduled_date = CURRENT_DATE)                             AS on_todays_board,
       count(*) FILTER (WHERE scheduled_date = CURRENT_DATE AND resource_id IS NOT NULL) AS placed_today,
       count(*) FILTER (WHERE scheduled_date = CURRENT_DATE AND resource_id IS NULL)     AS unassigned_today
FROM public.workorder
WHERE location_id = :location_id;

COMMIT;
