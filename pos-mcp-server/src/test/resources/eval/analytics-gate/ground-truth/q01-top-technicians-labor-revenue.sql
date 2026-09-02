-- Ground truth for gate question Q1 (analytics-capability-plan.md §6; tool-selection fixture
-- q01-top-technicians-labor-revenue-last-month):
--   "Who were our top technicians by labor revenue last month, and what was their average
--    billed hours per completed work order?"
--
-- Serving endpoint: E5 — GET /v1/workorders/analytics/technician-labor?startDate=&endDate=
--   (pos-workorder). Budget (§6): 2 tool calls. Tolerance (§2.1): exact for counts/currency,
--   ±0.5 % for the derived avg-hours-per-WO ratio. "Last month" against the TRACKB seed's
--   EVAL_AS_OF 2026-09-01 is 2026-08-01..2026-08-31.
--
-- SEMANTICS — mirrors WorkorderAnalyticsServiceImpl.getTechnicianLabor exactly:
--   * completedWoCount: work_order_state_transitions rows with to_status='COMPLETED' AND
--     from_status <> 'COMPLETED' (the COMPLETED->COMPLETED rows are reopen markers, not
--     completions), transitioned_at in [start 00:00Z, end+1d 00:00Z), attributed to the
--     COMPLETING actor: transitioned_by (username) -> ext_people_contact_user_link (ACTIVE)
--     -> person_id. Unresolvable usernames are dropped. Distinct workorders per technician.
--   * billedHours: workorder_labor_entry rows with end_time NOT NULL whose start_time (naive
--     LocalDateTime, seed writes UTC wall-clock) lies in [start 00:00, end+1d 00:00] —
--     the JPA `Between` bound is INCLUSIVE of the end+1d midnight instant (upstream quirk;
--     no seed row sits on that boundary). Attributed by the entry's own technician_id,
--     independent of whether/who completed the workorder.
--   * laborRevenue: sum of workorder-db ext_invoice.labor_total (NULLs excluded) over ALL
--     invoices of the technician's completed-in-window workorders — no date or status filter
--     on the invoice side. The deposit-pair workorder carries two invoices (deposit-take
--     labor_total 0.00 + settlement 1500.00), both summed.
--   * Endpoint sort is billedHours DESC; the model re-ranks by laborRevenue for the answer.
--
-- Usage: psql -v start_date="'2026-08-01'" -v end_date="'2026-08-31'" -f q01-... <pos_workorder_db>
-- DB: pos_workorder_db
\if :{?start_date}
\else
\set start_date '''2026-08-01'''
\endif
\if :{?end_date}
\else
\set end_date '''2026-08-31'''
\endif

WITH params AS (
    SELECT CAST(:start_date AS date) AS start_date, CAST(:end_date AS date) AS end_date
),
bounds AS (
    SELECT
        (start_date::timestamp AT TIME ZONE 'UTC')                    AS ts_start,   -- inclusive
        ((end_date + 1)::timestamp AT TIME ZONE 'UTC')                AS ts_end,     -- exclusive
        start_date::timestamp                                          AS labor_start, -- inclusive (naive)
        (end_date + 1)::timestamp                                      AS labor_end    -- INCLUSIVE (JPA Between quirk)
    FROM params
),
completions AS (
    SELECT DISTINCT l.person_id AS technician_id, t.workorder_id
    FROM work_order_state_transitions t
    JOIN ext_people_contact_user_link l
      ON l.username = t.transitioned_by AND l.status = 'ACTIVE'
    CROSS JOIN bounds b
    WHERE t.to_status = 'COMPLETED'
      AND t.from_status <> 'COMPLETED'
      AND t.transitioned_at >= b.ts_start AND t.transitioned_at < b.ts_end
),
completed_counts AS (
    SELECT technician_id, COUNT(*) AS completed_wo_count
    FROM completions GROUP BY technician_id
),
billed_hours AS (
    SELECT le.technician_id, SUM(le.hours_worked) AS billed_hours
    FROM workorder_labor_entry le
    CROSS JOIN bounds b
    WHERE le.end_time IS NOT NULL
      AND le.start_time >= b.labor_start AND le.start_time <= b.labor_end
    GROUP BY le.technician_id
),
labor_revenue AS (
    SELECT c.technician_id, SUM(i.labor_total) AS labor_revenue
    FROM completions c
    JOIN ext_invoice i ON i.workorder_id = c.workorder_id
    WHERE i.labor_total IS NOT NULL
    GROUP BY c.technician_id
),
technicians AS (
    SELECT technician_id FROM completed_counts
    UNION SELECT technician_id FROM billed_hours
    UNION SELECT technician_id FROM labor_revenue
)
SELECT
    t.technician_id,
    TRIM(COALESCE(p.first_name, '') || ' ' || COALESCE(p.last_name, ''))     AS name,
    COALESCE(cc.completed_wo_count, 0)                                        AS completed_wo_count,
    COALESCE(bh.billed_hours, 0)                                              AS billed_hours,
    COALESCE(lr.labor_revenue, 0)                                             AS labor_revenue,
    -- The model's derived figure: average billed hours per completed WO (±0.5 % tolerance).
    CASE WHEN COALESCE(cc.completed_wo_count, 0) = 0 THEN NULL
         ELSE ROUND(COALESCE(bh.billed_hours, 0) / cc.completed_wo_count, 2) END AS avg_hours_per_completed_wo
FROM technicians t
LEFT JOIN completed_counts cc ON cc.technician_id = t.technician_id
LEFT JOIN billed_hours bh     ON bh.technician_id = t.technician_id
LEFT JOIN labor_revenue lr    ON lr.technician_id = t.technician_id
LEFT JOIN ext_people_contact_person p ON p.person_id = t.technician_id
ORDER BY labor_revenue DESC, t.technician_id;
