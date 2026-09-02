-- Ground truth for gate question Q15 (analytics-capability-plan.md §6; tool-selection fixture
-- q15-top-vendors-six-month-year-over-year):
--   "Who were our largest vendors by spend over the last six months compared with the same
--    six months last year?"
--
-- Serving endpoint: E8 — GET /v1/accounting/analytics/vendor-spend?startDate=&endDate=&limit=
--   (pos-accounting), one call per window. Budget (§6): 3. Tolerance (§2.1): exact currency;
--   avgBillAmount is scale-2 HALF_UP. Windows against EVAL_AS_OF 2026-09-01:
--   2026-03-01..2026-08-31 vs 2025-03-01..2025-08-31 (the DATASET.md E8 windows).
--
-- SEMANTICS — mirrors AccountingAnalyticsServiceImpl.getVendorSpend exactly:
--   * paidAmount: SUM(ap_payment.gross_amount) for payments whose status is in the settled
--     set (GATEWAY_SUCCEEDED, GL_POST_PENDING, GL_POSTED, GL_POST_FAILED — GL posting state
--     does not change whether cash moved), with payment_date (a NAIVE LocalDateTime column)
--     in [start 00:00, end 23:59:59.999999].
--   * billCount / avgBillAmount: vendor_bill rows bucketed by bill_date (same naive-window
--     rule) REGARDLESS of bill status; avg = billTotal/billCount, scale 2 HALF_UP.
--   * Vendors ranked by paidAmount DESC; the union of payment-vendors and bill-vendors is
--     reported (a vendor can have bills but no settled payments in the window).
--
-- DIVERGENCE: paidAmount sums payment gross_amount by payment DATE with no allocation join —
--   a payment is attributed whole to its window even if allocated across bills from other
--   periods; and billCount counts bills by bill_date even when unpaid. In this seed every
--   payment fully allocates its same-month bill, so the two views coincide.
--
-- Usage: psql -f q15-... <pos_accounting_db>
-- DB: pos_accounting_db
WITH windows AS (
    SELECT 'last-6mo' AS window_label, date '2026-03-01' AS win_start, date '2026-08-31' AS win_end
    UNION ALL
    SELECT 'same-6mo-last-year', date '2025-03-01', date '2025-08-31'
),
paid AS (
    SELECT w.window_label, p.vendor_id, SUM(p.gross_amount) AS paid_amount
    FROM windows w
    JOIN ap_payment p
      ON p.payment_date >= w.win_start::timestamp
     AND p.payment_date <  (w.win_end + 1)::timestamp
    WHERE p.status IN ('GATEWAY_SUCCEEDED', 'GL_POST_PENDING', 'GL_POSTED', 'GL_POST_FAILED')
    GROUP BY w.window_label, p.vendor_id
),
bills AS (
    SELECT w.window_label, b.vendor_id, COUNT(*) AS bill_count, SUM(b.total_amount) AS bill_total
    FROM windows w
    JOIN vendor_bill b
      ON b.bill_date >= w.win_start::timestamp
     AND b.bill_date <  (w.win_end + 1)::timestamp
    GROUP BY w.window_label, b.vendor_id
),
merged AS (
    SELECT COALESCE(p.window_label, b.window_label) AS window_label,
           COALESCE(p.vendor_id, b.vendor_id)       AS vendor_id,
           COALESCE(p.paid_amount, 0)               AS paid_amount,
           COALESCE(b.bill_count, 0)                AS bill_count,
           CASE WHEN COALESCE(b.bill_count, 0) = 0 THEN 0
                ELSE ROUND(b.bill_total / b.bill_count, 2) END AS avg_bill_amount
    FROM paid p
    FULL OUTER JOIN bills b ON (b.window_label, b.vendor_id) = (p.window_label, p.vendor_id)
)
SELECT m.window_label, m.vendor_id, v.name, m.paid_amount, m.bill_count, m.avg_bill_amount
FROM merged m
LEFT JOIN ap_vendor v ON v.vendor_id = m.vendor_id
ORDER BY m.window_label, m.paid_amount DESC, m.vendor_id;
