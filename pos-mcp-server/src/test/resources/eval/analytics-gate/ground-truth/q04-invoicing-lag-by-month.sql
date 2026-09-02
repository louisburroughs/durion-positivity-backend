-- Ground truth for gate question Q4 (analytics-capability-plan.md §6):
--   "Avg WO-creation→invoice time by month, 6 months" — average time from workorder creation
--   to invoice creation, by month, for the last six months.
--
-- Serving endpoint: E4 — GET /v1/invoices/analytics/invoicing-lag?startDate=&endDate=
--   (pos-invoice), one call per month (6 calls; 1 call in Wave 3 via groupBy). Budget (§6): 7.
--   Tolerance (§2.1): ±0.5 % on the derived average. Months against EVAL_AS_OF 2026-09-01:
--   2026-03 .. 2026-08.
--
-- SEMANTICS — mirrors InvoiceAnalyticsServiceImpl.invoicingLag / InvoiceRepository
--   .invoicingLagPairs exactly:
--   * Universe: invoices with workorder_id NOT NULL, created_at in the window
--     [start 00:00Z, end 23:59:59.999999Z] — NO status filter (drafts and cancelled would
--     count; the seed has none) and NO deposit filter of its own.
--   * Anchor: LEFT JOIN ext_workorder (pos-invoice's replica) on workorder_id; a missing
--     replica row OR a NULL workorder_created_at EXCLUDES the invoice from both the average
--     and the count — never treated as zero lag (#1592 null-anchor rule).
--   * avgDaysWoCreationToInvoice = mean of (invoice.created_at - workorder_created_at) in
--     fractional days (millisecond arithmetic in the impl; epoch-seconds here — identical to
--     well below the tolerance).
--
-- DIVERGENCE: the two deposit-pair invoices (Aug 2026) carry a workorder whose replica
--   anchor workorder_created_at is deliberately NULL, so they are excluded from August's
--   average by the #1592 rule (DATASET.md, deposit-pair E4 protection) — a human reading
--   "all WO-linked invoices" would expect 7 August pairs; the endpoint averages 5.
--
-- Usage: psql -f q04-... <pos_invoice_db>   (months fixed to the seed's last six)
-- DB: pos_invoice_db
WITH months AS (
    SELECT generate_series(date '2026-03-01', date '2026-08-01', interval '1 month')::date AS month_start
),
pairs AS (
    SELECT
        m.month_start,
        i.created_at   AS invoice_created_at,
        w.workorder_created_at
    FROM months m
    JOIN invoices i
      ON i.created_at >= (m.month_start::timestamp AT TIME ZONE 'UTC')
     AND i.created_at <  ((m.month_start + interval '1 month')::timestamp AT TIME ZONE 'UTC')
    LEFT JOIN ext_workorder w ON w.workorder_id = i.workorder_id
    WHERE i.workorder_id IS NOT NULL
)
SELECT
    month_start,
    ROUND(AVG(EXTRACT(EPOCH FROM (invoice_created_at - workorder_created_at)) / 86400.0)
              FILTER (WHERE workorder_created_at IS NOT NULL), 4) AS avg_days_wo_creation_to_invoice,
    COUNT(*) FILTER (WHERE workorder_created_at IS NOT NULL)      AS pair_count,
    COUNT(*) FILTER (WHERE workorder_created_at IS NULL)          AS excluded_null_anchor
FROM pairs
GROUP BY month_start
ORDER BY month_start;
