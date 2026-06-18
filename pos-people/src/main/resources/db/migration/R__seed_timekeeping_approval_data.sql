-- Repeatable seed for pay-period timekeeping approval (issue #664 flow).
-- Creates time periods and timekeeping entries so /app/people/timekeeping/approval
-- has data: one OPEN period with PENDING entries (exercisable approve/reject) for
-- 5 employees, plus a historical PAYROLL_CLOSED period of APPROVED entries.
--
-- Employees referenced are the EMP-0001..0005 rows from
-- R__seed_people_operational_data.sql (01960011-0000-7000-8000-00000000000{1..5}).
-- timekeeping_entry links to a period by date range (session times within
-- [start_date 00:00, end_date 23:59:59]); there is no FK column.
SET TIME ZONE 'UTC';

-- ── Tenant (display path ignores tenant; value just needs to be stable) ──────
-- 01960030-* = time periods, 01960031-* = timekeeping entries, source_session 01960032-*
-- tenant: 01960000-0000-7000-8000-000000000001

-- ── Pay periods ──────────────────────────────────────────────────────────────
INSERT INTO time_period (time_period_id, tenant_id, start_date, end_date, status, created_at, updated_at)
VALUES
    ('01960030-0000-7000-8000-000000000001'::uuid, '01960000-0000-7000-8000-000000000001'::uuid,
        DATE '2026-05-18', DATE '2026-05-31', 'PAYROLL_CLOSED', NOW(), NOW()),
    ('01960030-0000-7000-8000-000000000002'::uuid, '01960000-0000-7000-8000-000000000001'::uuid,
        DATE '2026-06-01', DATE '2026-06-14', 'OPEN', NOW(), NOW())
ON CONFLICT (time_period_id) DO NOTHING;

-- ── PENDING entries in the OPEN period (2026-06-01 .. 2026-06-14) ────────────
-- 5 employees x 3 daily 8h shifts = 15 entries, all PENDING_APPROVAL.
INSERT INTO timekeeping_entry (
    timekeeping_entry_id, tenant_id, source_system, source_session_id,
    employee_id, session_start_time, session_end_time, approval_status, created_at)
SELECT
    ('01960031-0000-7000-8000-0000' || lpad(emp.n::text, 4, '0') || lpad(d.day::text, 4, '0'))::uuid,
    '01960000-0000-7000-8000-000000000001'::uuid,
    'shopmgr',
    ('01960032-0000-7000-8000-0000' || lpad(emp.n::text, 4, '0') || lpad(d.day::text, 4, '0'))::uuid,
    ('01960011-0000-7000-8000-00000000000' || emp.n::text)::uuid,
    ((DATE '2026-06-01' + (d.day - 1)) + TIME '08:00') AT TIME ZONE 'UTC',
    ((DATE '2026-06-01' + (d.day - 1)) + TIME '16:00') AT TIME ZONE 'UTC',
    'PENDING_APPROVAL',
    NOW()
FROM (VALUES (1), (2), (3), (4), (5)) AS emp(n)
CROSS JOIN (VALUES (2), (3), (4)) AS d(day)   -- 2026-06-02, 06-03, 06-04
ON CONFLICT (timekeeping_entry_id) DO NOTHING;

-- ── APPROVED entries in the closed period (history) for EMP-0001 ─────────────
INSERT INTO timekeeping_entry (
    timekeeping_entry_id, tenant_id, source_system, source_session_id,
    employee_id, session_start_time, session_end_time, approval_status, created_at)
SELECT
    ('01960031-0000-7000-8000-00010000' || lpad(d.day::text, 4, '0'))::uuid,
    '01960000-0000-7000-8000-000000000001'::uuid,
    'shopmgr',
    ('01960032-0000-7000-8000-00010000' || lpad(d.day::text, 4, '0'))::uuid,
    '01960011-0000-7000-8000-000000000001'::uuid,
    ((DATE '2026-05-18' + (d.day - 1)) + TIME '08:00') AT TIME ZONE 'UTC',
    ((DATE '2026-05-18' + (d.day - 1)) + TIME '16:00') AT TIME ZONE 'UTC',
    'APPROVED',
    NOW()
FROM (VALUES (1), (2), (3)) AS d(day)   -- 2026-05-18, 05-19, 05-20
ON CONFLICT (timekeeping_entry_id) DO NOTHING;
