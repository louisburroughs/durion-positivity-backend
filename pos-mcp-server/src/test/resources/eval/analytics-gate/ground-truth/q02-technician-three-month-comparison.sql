-- Ground truth for gate question Q2 (analytics-capability-plan.md §6):
--   "Technician completed WOs / hours / revenue, 3-month comparison" — for each technician,
--   completed workorders, billed hours and labor revenue for each of the last three months.
--
-- Serving endpoint: Wave 3 `technicianPerformance(period[, months])` composition (plan §W3.2);
--   early Wave 2 pass allowed via 3 × E5 — GET /v1/workorders/analytics/technician-labor —
--   one call per month. Budget (§6): 2 (W3 composition; 3 under the W2 early-pass loop).
--   Tolerance (§2.1): exact for counts/currency/hours. Months against EVAL_AS_OF 2026-09-01:
--   2026-06, 2026-07, 2026-08.
--
-- SEMANTICS — identical to q01 (mirrors WorkorderAnalyticsServiceImpl.getTechnicianLabor),
--   evaluated per calendar month; see q01's header for the completion/hours/revenue rules.
--   The W3.2 composition is specified as "E5 grouped" — this script is written against the
--   E5 per-window semantics and is the truth the composition must land into (SPEC-AHEAD:
--   the composition does not exist yet; if its shipped semantics differ, this script is the
--   review tripwire, not the thing to silently edit).
--
-- Usage: psql -f q02-... <pos_workorder_db>   (months fixed to the seed's last three)
-- DB: pos_workorder_db
WITH months AS (
    SELECT date '2026-06-01' AS month_start, date '2026-07-01' AS month_end_excl
    UNION ALL SELECT date '2026-07-01', date '2026-08-01'
    UNION ALL SELECT date '2026-08-01', date '2026-09-01'
),
completions AS (
    SELECT DISTINCT m.month_start, l.person_id AS technician_id, t.workorder_id
    FROM months m
    JOIN work_order_state_transitions t
      ON t.transitioned_at >= (m.month_start::timestamp AT TIME ZONE 'UTC')
     AND t.transitioned_at <  (m.month_end_excl::timestamp AT TIME ZONE 'UTC')
    JOIN ext_people_contact_user_link l
      ON l.username = t.transitioned_by AND l.status = 'ACTIVE'
    WHERE t.to_status = 'COMPLETED' AND t.from_status <> 'COMPLETED'
),
completed_counts AS (
    SELECT month_start, technician_id, COUNT(*) AS completed_wo_count
    FROM completions GROUP BY month_start, technician_id
),
billed_hours AS (
    SELECT m.month_start, le.technician_id, SUM(le.hours_worked) AS billed_hours
    FROM months m
    JOIN workorder_labor_entry le
      ON le.end_time IS NOT NULL
     AND le.start_time >= m.month_start::timestamp
     AND le.start_time <= m.month_end_excl::timestamp   -- inclusive upper bound (JPA Between; see q01)
    GROUP BY m.month_start, le.technician_id
),
labor_revenue AS (
    SELECT c.month_start, c.technician_id, SUM(i.labor_total) AS labor_revenue
    FROM completions c
    JOIN ext_invoice i ON i.workorder_id = c.workorder_id
    WHERE i.labor_total IS NOT NULL
    GROUP BY c.month_start, c.technician_id
),
tech_months AS (
    SELECT month_start, technician_id FROM completed_counts
    UNION SELECT month_start, technician_id FROM billed_hours
    UNION SELECT month_start, technician_id FROM labor_revenue
)
SELECT
    tm.month_start,
    tm.technician_id,
    TRIM(COALESCE(p.first_name, '') || ' ' || COALESCE(p.last_name, '')) AS name,
    COALESCE(cc.completed_wo_count, 0) AS completed_wo_count,
    COALESCE(bh.billed_hours, 0)       AS billed_hours,
    COALESCE(lr.labor_revenue, 0)      AS labor_revenue
FROM tech_months tm
LEFT JOIN completed_counts cc ON (cc.month_start, cc.technician_id) = (tm.month_start, tm.technician_id)
LEFT JOIN billed_hours bh     ON (bh.month_start, bh.technician_id) = (tm.month_start, tm.technician_id)
LEFT JOIN labor_revenue lr    ON (lr.month_start, lr.technician_id) = (tm.month_start, tm.technician_id)
LEFT JOIN ext_people_contact_person p ON p.person_id = tm.technician_id
ORDER BY tm.month_start, labor_revenue DESC, tm.technician_id;
