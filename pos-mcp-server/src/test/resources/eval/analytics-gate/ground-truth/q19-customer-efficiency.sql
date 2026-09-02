-- Ground truth for gate question Q19 (analytics-capability-plan.md §6):
--   "Revenue vs technician hours by customer, revenue/hour" — for each customer, invoiced
--   revenue against technician hours worked, and revenue per technician hour.
--
-- Serving endpoint: Wave 3 `customerEfficiency(startDate, endDate)` composition (plan §W3.2:
--   E1 ⨝ E5-by-customer). Budget (§6): 2. Tolerance (§2.1): exact currency/hours; revenue
--   per hour is a derived ratio (±0.5 %). Window: last month against EVAL_AS_OF 2026-09-01 =
--   2026-08-01..2026-08-31 (aligned with Q1's window so every figure is hand-checkable from
--   DATASET.md's August design).
--
-- SEMANTICS — SPEC-AHEAD: the composition does not exist yet; this script is the truth it
--   must land into, built from its two specified members' shipped semantics:
--   Section 1 (pos_invoice_db) = E1 revenue-by-customer exactly (q07 header rules).
--   Section 2 (pos_workorder_db) = the E5 hours rule dimensioned by customer instead of
--   technician: workorder_labor_entry rows with end_time NOT NULL and start_time (naive) in
--   [start 00:00, end+1d 00:00], attributed to the workorder's customer_id. Hours attribute
--   to the customer whose workorder was worked, whether or not that workorder is invoiced in
--   the window (same window-mismatch caveat E5 carries for technicians).
--   The composed answer divides section 1's revenue by section 2's hours per customer
--   (model/composition arithmetic; no cross-database join exists).
--   If the shipped composition's semantics differ, this script is the review tripwire — do
--   not silently edit it to match (plan §7 fixture-drift rule).
--
-- Usage: run via run_ground_truth.sh.
-- DB: pos_invoice_db
WITH win AS (
    SELECT date '2026-08-01' AS win_start, date '2026-08-31' AS win_end
)
SELECT i.customer_id,
       cp.display_name     AS name,
       SUM(i.total_amount) AS revenue,
       COUNT(*)            AS invoice_count
FROM win w
JOIN invoices i
  ON i.created_at >= (w.win_start::timestamp AT TIME ZONE 'UTC')
 AND i.created_at <  ((w.win_end + 1)::timestamp AT TIME ZONE 'UTC')
LEFT JOIN ext_customer_party cp ON cp.party_id::text = i.customer_id
WHERE i.customer_id IS NOT NULL
  AND i.status IN ('FINALIZED', 'POSTED')
  AND i.deposit_source_type IS NULL
GROUP BY i.customer_id, cp.display_name
ORDER BY revenue DESC, i.customer_id;

-- DB: pos_workorder_db
-- Technician hours by customer, August 2026 (E5 hours rule, customer dimension).
WITH win AS (
    SELECT timestamp '2026-08-01 00:00:00' AS labor_start,
           timestamp '2026-09-01 00:00:00' AS labor_end   -- inclusive bound (JPA Between; see q01)
)
SELECT w.customer_id,
       cp.display_name        AS name,
       SUM(le.hours_worked)   AS technician_hours
FROM win b
JOIN workorder_labor_entry le
  ON le.end_time IS NOT NULL
 AND le.start_time >= b.labor_start AND le.start_time <= b.labor_end
JOIN workorder w ON w.id = le.workorder_id
LEFT JOIN ext_customer_party cp ON cp.party_id = w.customer_id
GROUP BY w.customer_id, cp.display_name
ORDER BY w.customer_id;
