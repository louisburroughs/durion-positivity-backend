-- Ground truth for gate question Q3 (analytics-capability-plan.md §6):
--   "Most WOs reopened ≤ 7 days of completion, this quarter" — which technician had the most
--   workorders reopened within 7 days of their completion, this quarter?
--
-- Serving endpoints: E6 — GET /v1/workorders/analytics/reopened?startDate=&endDate=&withinDays=7
--   (pos-workorder), backed by the E7 status-transition projection (plan D4). Budget (§6): 2.
--   Tolerance (§2.1): exact counts. "This quarter" against EVAL_AS_OF 2026-09-01 is
--   2026-07-01..2026-09-01 (the DATASET.md quarter window).
--
-- SEMANTICS — mirrors WorkorderAnalyticsServiceImpl.getReopenedWorkorders exactly:
--   * A COMPLETION is a transition to COMPLETED with from_status <> COMPLETED, anchored in
--     [startDate 00:00Z, endDate+1d 00:00Z).
--   * A REOPEN is the same-status COMPLETED -> COMPLETED marker row that
--     WorkorderStateMachine.reopenCompletedWorkorder records (reason prefixed 'Reopened: ');
--     the workorder's status never leaves COMPLETED on reopen (DATASET.md deviation 6).
--     Markers are fetched out to endDate+1d+withinDays so completions near the window edge
--     still pair.
--   * A pair counts when marker.transitioned_at > completion.transitioned_at AND
--     marker.transitioned_at <= completion.transitioned_at + withinDays days.
--   * Attribution: the COMPLETING actor's username -> ext_people_contact_user_link (ACTIVE)
--     -> person_id; unresolvable actors are dropped.
--   Row set is (technicianId, woId, completedAt, reopenedAt), sorted reopenedAt, woId;
--   the MODEL counts per technician (second result set below is that count).
--
-- Usage: psql -v start_date="'2026-07-01'" -v end_date="'2026-09-01'" -v within_days=7 -f q03-... <pos_workorder_db>
-- DB: pos_workorder_db
\if :{?start_date}
\else
\set start_date '''2026-07-01'''
\endif
\if :{?end_date}
\else
\set end_date '''2026-09-01'''
\endif
\if :{?within_days}
\else
\set within_days 7
\endif

WITH params AS (
    SELECT CAST(:start_date AS date) AS start_date,
           CAST(:end_date AS date)   AS end_date,
           CAST(:within_days AS int) AS within_days
),
bounds AS (
    SELECT (start_date::timestamp AT TIME ZONE 'UTC')       AS ts_start,
           ((end_date + 1)::timestamp AT TIME ZONE 'UTC')   AS ts_end,
           within_days
    FROM params
),
completions AS (
    SELECT l.person_id AS technician_id, t.workorder_id, t.transitioned_at AS completed_at
    FROM work_order_state_transitions t
    JOIN ext_people_contact_user_link l
      ON l.username = t.transitioned_by AND l.status = 'ACTIVE'
    CROSS JOIN bounds b
    WHERE t.to_status = 'COMPLETED' AND t.from_status <> 'COMPLETED'
      AND t.transitioned_at >= b.ts_start AND t.transitioned_at < b.ts_end
),
markers AS (
    SELECT t.workorder_id, t.transitioned_at AS reopened_at
    FROM work_order_state_transitions t
    CROSS JOIN bounds b
    WHERE t.to_status = 'COMPLETED' AND t.from_status = 'COMPLETED'
      AND t.transitioned_at >= b.ts_start
      AND t.transitioned_at <  b.ts_end + make_interval(days => b.within_days)
),
pairs AS (
    SELECT c.technician_id, c.workorder_id, c.completed_at, m.reopened_at
    FROM completions c
    JOIN markers m ON m.workorder_id = c.workorder_id
    CROSS JOIN bounds b
    WHERE m.reopened_at >  c.completed_at
      AND m.reopened_at <= c.completed_at + make_interval(days => b.within_days)
)
SELECT p.technician_id,
       TRIM(COALESCE(pe.first_name, '') || ' ' || COALESCE(pe.last_name, '')) AS name,
       p.workorder_id, p.completed_at, p.reopened_at
FROM pairs p
LEFT JOIN ext_people_contact_person pe ON pe.person_id = p.technician_id
ORDER BY p.reopened_at, p.workorder_id;

-- The model's derived answer: reopen count per technician, most first.
WITH params AS (
    SELECT CAST(:start_date AS date) AS start_date,
           CAST(:end_date AS date)   AS end_date,
           CAST(:within_days AS int) AS within_days
),
bounds AS (
    SELECT (start_date::timestamp AT TIME ZONE 'UTC')       AS ts_start,
           ((end_date + 1)::timestamp AT TIME ZONE 'UTC')   AS ts_end,
           within_days
    FROM params
),
completions AS (
    SELECT l.person_id AS technician_id, t.workorder_id, t.transitioned_at AS completed_at
    FROM work_order_state_transitions t
    JOIN ext_people_contact_user_link l
      ON l.username = t.transitioned_by AND l.status = 'ACTIVE'
    CROSS JOIN bounds b
    WHERE t.to_status = 'COMPLETED' AND t.from_status <> 'COMPLETED'
      AND t.transitioned_at >= b.ts_start AND t.transitioned_at < b.ts_end
),
markers AS (
    SELECT t.workorder_id, t.transitioned_at AS reopened_at
    FROM work_order_state_transitions t
    CROSS JOIN bounds b
    WHERE t.to_status = 'COMPLETED' AND t.from_status = 'COMPLETED'
      AND t.transitioned_at >= b.ts_start
      AND t.transitioned_at <  b.ts_end + make_interval(days => b.within_days)
)
SELECT c.technician_id,
       TRIM(COALESCE(pe.first_name, '') || ' ' || COALESCE(pe.last_name, '')) AS name,
       COUNT(*) AS reopens_within_window
FROM completions c
JOIN markers m ON m.workorder_id = c.workorder_id
CROSS JOIN bounds b
LEFT JOIN ext_people_contact_person pe ON pe.person_id = c.technician_id
WHERE m.reopened_at > c.completed_at
  AND m.reopened_at <= c.completed_at + make_interval(days => b.within_days)
GROUP BY c.technician_id, pe.first_name, pe.last_name
ORDER BY reopens_within_window DESC, c.technician_id;
