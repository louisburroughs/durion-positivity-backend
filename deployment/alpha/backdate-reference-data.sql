-- One-off back-dating of reference data already loaded on alpha (#2083, #2084).
--
-- The accelerated clock runs a year behind wall time. Anything effective-dated from the day it was
-- loaded does not exist at an earlier virtual instant, so an accelerated run finds nobody staffed
-- and no labor rate for most of its year. The fixtures and seeds now carry early dates, which fixes
-- a fresh environment; this script moves the rows an existing environment already holds.
--
-- Run as the database superuser (RLS is bypassed, so every tenant's rows are seen), from a host that
-- can reach every service database:
--
--   psql -h <host> -U postgres -v ON_ERROR_STOP=1 -f backdate-reference-data.sql
--
-- Only ever moves a start earlier, never later, and never touches a row that has ended. Safe to
-- re-run: a second run finds nothing left to move.
--
-- updated_at is left alone on purpose. ADR-0024 keeps database time out of the write half of an
-- UPDATE: now() is wall time, and run while the accelerated profile is on it would stamp these rows
-- a year ahead of everything the application writes. This is a data correction, not an application
-- write, so there is no application clock to take a value from.
--
-- Not covered here, deliberately:
--   * base prices, GL mappings and the seeded admin role assignments: their R__ seeds now write an
--     early date and back-date the stored row, so the next deploy moves them;
--   * role assignments granted to fixture users at load time: the SDK harness bridges those
--     (durion-positivity-sdk#67).

\set ON_ERROR_STOP 1

-- ---------------------------------------------------------------------------
-- 1. pos-people: staffing starts on the employee's hire date
-- ---------------------------------------------------------------------------
\connect pos_people_db

BEGIN;

-- One candidate per assignment: active, open-ended, and the employee's only assignment, so moving
-- its start cannot overlap another row for the same person.
CREATE TEMP TABLE staffing_backdate ON COMMIT DROP AS
SELECT ela.tenant_id, ela.id, ela.effective_from AS old_from, e.hire_date AS new_from
FROM employee_location_assignment ela
JOIN employee e ON e.id = ela.employee_id AND e.tenant_id = ela.tenant_id
WHERE ela.status = 'ACTIVE'
  AND ela.effective_to IS NULL
  AND e.hire_date IS NOT NULL
  AND e.hire_date < ela.effective_from
  AND NOT EXISTS (
      SELECT 1 FROM employee_location_assignment other
      WHERE other.tenant_id = ela.tenant_id
        AND other.employee_id = ela.employee_id
        AND other.id <> ela.id);

SELECT count(*) AS staffing_rows_to_backdate FROM staffing_backdate;

UPDATE employee_location_assignment ela
SET effective_from = b.new_from
FROM staffing_backdate b
WHERE ela.tenant_id = b.tenant_id AND ela.id = b.id;

-- The security replica is in another database; carry the same moves across by id.
\pset tuples_only on
\pset format unaligned
\o /tmp/backdate-staffing-replica.sql
SELECT format(
    'UPDATE ext_people_staffing_assignment SET effective_from = %L WHERE tenant_id = %L AND assignment_id = %L AND (effective_from IS NULL OR effective_from > %L);',
    new_from, tenant_id, id, new_from)
FROM staffing_backdate;
\o
\pset tuples_only off
\pset format aligned

COMMIT;

-- ---------------------------------------------------------------------------
-- 2. pos-security-service: the staffing replica that feeds loc_scope
-- ---------------------------------------------------------------------------
\connect pos_security_db

BEGIN;
\i /tmp/backdate-staffing-replica.sql
COMMIT;

-- ---------------------------------------------------------------------------
-- 3. pos-price: fixture labor rates and adjustments start with the base prices
-- ---------------------------------------------------------------------------
\connect pos_price_db

BEGIN;

-- The fixtures loaded these at 2026-01-01; they now say 2024-01-01. Skip a row whose scope already
-- holds a 2024-01-01 start, which a re-seed with the new fixtures would have created.
UPDATE labor_rate r
SET effective_from = TIMESTAMPTZ '2024-01-01 00:00:00+00'
WHERE r.effective_from = TIMESTAMPTZ '2026-01-01 00:00:00+00'
  AND r.effective_to IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM labor_rate o
      WHERE o.tenant_id = r.tenant_id
        AND o.location_id IS NOT DISTINCT FROM r.location_id
        AND o.operation_category IS NOT DISTINCT FROM r.operation_category
        AND o.effective_from = TIMESTAMPTZ '2024-01-01 00:00:00+00');

UPDATE labor_rate_adjustment a
SET effective_from = TIMESTAMPTZ '2024-01-01 00:00:00+00'
WHERE a.effective_from = TIMESTAMPTZ '2026-01-01 00:00:00+00'
  AND a.effective_to IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM labor_rate_adjustment o
      WHERE o.tenant_id = a.tenant_id
        AND o.adjustment_code = a.adjustment_code
        AND o.location_id IS NOT DISTINCT FROM a.location_id
        AND o.operation_category IS NOT DISTINCT FROM a.operation_category
        AND o.effective_from = TIMESTAMPTZ '2024-01-01 00:00:00+00');

COMMIT;
