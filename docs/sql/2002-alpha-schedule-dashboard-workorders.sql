-- Alpha data repair for #2002 — make the daily dispatch board non-empty.
--
-- Alpha holds non-terminal workorders at one repair-capable location, every one of them with
-- scheduled_date NULL. The board selects its roster on the date, so it renders empty at every
-- location and date and the daily dispatch workflow (#60) cannot be demonstrated.
--
-- This script repairs the existing rows rather than inserting fixtures: alpha already has the
-- workorders, the locations, the bays and the mobile units. It gives every open workorder at the
-- repair location today's business date, then places two of them on free active bays and one on a
-- free active mobile unit, leaving the rest unplaced so the roster shows both assigned and
-- unassigned work.
--
-- Safe to re-run. Every statement is guarded on the state it creates: the schedule update only
-- touches NULL dates, the placements only touch workorders with no position and only use resources
-- no open workorder holds, and the history insert skips workorders that already have a current row.
--
-- Run it against the pos-workorder database, in one transaction:
--
--   psql "$POS_WORKORDER_URL" -v tenant_id="'<tenant-uuid>'" \
--        -f docs/sql/2002-alpha-schedule-dashboard-workorders.sql
--
-- The tenant must be bound before the first statement (ADR-0062): every table below is under row
-- level security keyed on tenant_id, so an unbound session sees nothing and updates nothing. The
-- alpha default tenant is the value of pos.tenancy.default-tenant-id.

\set ON_ERROR_STOP on

BEGIN;

SELECT set_config('app.current_tenant', :tenant_id, true);

-- ---------------------------------------------------------------------------
-- 0. Before: what the board is working from.
-- ---------------------------------------------------------------------------
SELECT 'before' AS phase,
       location_id,
       count(*)                                          AS workorders,
       count(*) FILTER (WHERE scheduled_date IS NULL)    AS unscheduled,
       count(*) FILTER (WHERE resource_id IS NOT NULL)   AS placed
FROM public.workorder
GROUP BY location_id
ORDER BY location_id;

-- ---------------------------------------------------------------------------
-- 1. Every open workorder gets today's business date.
-- ---------------------------------------------------------------------------
-- "Open" mirrors Workorder.isLocked(): CANCELLED is closed, COMPLETED is closed unless the
-- workorder was reopened, and a NULL status is open (it is the DRAFT the builder defaults to).
-- Closed workorders are deliberately left undated — back-dating finished work would put it on a
-- board it was never on.
UPDATE public.workorder
SET scheduled_date = CURRENT_DATE,
    updated_at     = now()
WHERE scheduled_date IS NULL
  AND location_id IS NOT NULL
  AND (status IS NULL OR status <> 'CANCELLED')
  AND (status IS NULL OR status <> 'COMPLETED' OR is_reopened IS TRUE);

-- ---------------------------------------------------------------------------
-- 2. Place two workorders on free active bays.
-- ---------------------------------------------------------------------------
-- Pairing is by row_number so the choice is deterministic and the partial unique index
-- workorder_open_position_uniq (one open workorder per exclusive position) cannot be violated:
-- each bay is used once, and bays already holding open work are excluded outright.
WITH free_bays AS (
    SELECT b.bay_id,
           b.location_id,
           row_number() OVER (PARTITION BY b.location_id ORDER BY b.bay_id) AS rn
    FROM public.ext_bay b
    WHERE b.active
      AND NOT EXISTS (
          SELECT 1
          FROM public.workorder held
          WHERE held.resource_id = b.bay_id
            AND held.resource_type = 'BAY'
            AND (held.status IS NULL OR held.status <> 'CANCELLED')
            AND (held.status IS NULL OR held.status <> 'COMPLETED' OR held.is_reopened IS TRUE))
),
placeable AS (
    SELECT w.id,
           w.location_id,
           row_number() OVER (PARTITION BY w.location_id ORDER BY w.id) AS rn
    FROM public.workorder w
    WHERE w.resource_id IS NULL
      AND w.scheduled_date = CURRENT_DATE
      AND w.status IN ('APPROVED', 'ASSIGNED', 'WORK_IN_PROGRESS')
),
pairing AS (
    SELECT p.id AS workorder_id, f.bay_id
    FROM placeable p
    JOIN free_bays f ON f.location_id = p.location_id AND f.rn = p.rn
    WHERE p.rn <= 2
)
UPDATE public.workorder w
SET resource_id   = pairing.bay_id,
    resource_type = 'BAY',
    updated_at    = now()
FROM pairing
WHERE w.id = pairing.workorder_id;

-- ---------------------------------------------------------------------------
-- 3. Place one workorder on a free active mobile unit.
-- ---------------------------------------------------------------------------
WITH free_units AS (
    SELECT u.mobile_unit_id,
           u.base_location_id,
           row_number() OVER (PARTITION BY u.base_location_id ORDER BY u.mobile_unit_id) AS rn
    FROM public.ext_mobile_unit u
    WHERE u.active
      AND NOT EXISTS (
          SELECT 1
          FROM public.workorder held
          WHERE held.resource_id = u.mobile_unit_id
            AND held.resource_type = 'MOBILE_UNIT'
            AND (held.status IS NULL OR held.status <> 'CANCELLED')
            AND (held.status IS NULL OR held.status <> 'COMPLETED' OR held.is_reopened IS TRUE))
),
placeable AS (
    SELECT w.id,
           w.location_id,
           row_number() OVER (PARTITION BY w.location_id ORDER BY w.id) AS rn
    FROM public.workorder w
    WHERE w.resource_id IS NULL
      AND w.scheduled_date = CURRENT_DATE
      AND w.status IN ('APPROVED', 'ASSIGNED', 'WORK_IN_PROGRESS')
),
pairing AS (
    SELECT p.id AS workorder_id, f.mobile_unit_id
    FROM placeable p
    JOIN free_units f ON f.base_location_id = p.location_id AND f.rn = p.rn
    WHERE p.rn = 1
)
UPDATE public.workorder w
SET resource_id   = pairing.mobile_unit_id,
    resource_type = 'MOBILE_UNIT',
    updated_at    = now()
FROM pairing
WHERE w.id = pairing.workorder_id;

-- ---------------------------------------------------------------------------
-- 4. A history row for each placement this script made.
-- ---------------------------------------------------------------------------
-- The workorder row says where the job is now; service_position_assignment says how it got there,
-- and the release path reads it. A placement with no current row would leave the history lying.
INSERT INTO public.service_position_assignment (
    workorder_id, resource_type, resource_id, location_id,
    assigned_at, assigned_by, reason, current, created_at, updated_at)
SELECT w.id, w.resource_type, w.resource_id, w.location_id,
       date_trunc('second', now() AT TIME ZONE 'UTC'), 'System:AlphaDataRepair',
       'Alpha dispatch-board fixtures (#2002)', TRUE, now(), now()
FROM public.workorder w
WHERE w.resource_id IS NOT NULL
  AND w.resource_type IN ('BAY', 'MOBILE_UNIT')
  AND NOT EXISTS (
      SELECT 1
      FROM public.service_position_assignment a
      WHERE a.workorder_id = w.id
        AND a.current);

-- ---------------------------------------------------------------------------
-- 5. After: what the board will render.
-- ---------------------------------------------------------------------------
SELECT 'after' AS phase,
       location_id,
       count(*) FILTER (WHERE scheduled_date = CURRENT_DATE)                           AS on_todays_board,
       count(*) FILTER (WHERE scheduled_date = CURRENT_DATE AND resource_id IS NOT NULL) AS placed_today,
       count(*) FILTER (WHERE scheduled_date = CURRENT_DATE AND resource_id IS NULL)     AS unassigned_today
FROM public.workorder
GROUP BY location_id
ORDER BY location_id;

COMMIT;
