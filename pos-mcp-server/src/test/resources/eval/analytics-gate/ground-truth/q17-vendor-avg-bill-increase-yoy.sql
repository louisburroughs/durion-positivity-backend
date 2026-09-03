-- Ground truth for gate question Q17 (analytics-capability-plan.md §6; tool-selection fixture
-- q17-vendors-average-bill-increase-yoy):
--   "Which vendors have increased their average bill amount by more than 10% year over year?"
--
-- Serving endpoint: E8 — GET /v1/accounting/analytics/vendor-spend (avgIssuedBillAmount), one
--   call per window. Budget (§6): 3. Tolerance (§2.1): avgIssuedBillAmount exact (scale-2 HALF_UP);
--   the YoY % is a derived ratio (±0.5 %). Windows: 2026-03-01..2026-08-31 vs
--   2025-03-01..2025-08-31 (the DATASET.md Q17 windows — the four Q16 bills are dated
--   2026-09-01 precisely to stay OUT of these windows).
--
-- SEMANTICS — E8's avgIssuedBillAmount (q15 header): vendor_bill rows bucketed by bill_date
--   (naive window), REGARDLESS of payment status, avg = SUM(total_amount)/COUNT, scale 2
--   HALF_UP; the model compares the two windows' avgIssuedBillAmount and keeps vendors
--   whose increase exceeds 10 %. Designed answer: V1 Evergreen alone (+12.0 %).
--
-- DIVERGENCE: avgIssuedBillAmount includes unpaid/rejected/voided bills (bill_date basis, no
--   allocation or status test). Nothing in these seed windows exercises that, by design.
--
-- Usage: psql -f q17-... <pos_accounting_db>
-- DB: pos_accounting_db
WITH cur AS (
    SELECT b.vendor_id, COUNT(*) AS bills_issued_in_window, ROUND(SUM(b.total_amount) / COUNT(*), 2) AS avg_issued_bill
    FROM vendor_bill b
    WHERE b.bill_date >= timestamp '2026-03-01 00:00:00' AND b.bill_date < timestamp '2026-09-01 00:00:00'
    GROUP BY b.vendor_id
),
prior AS (
    SELECT b.vendor_id, COUNT(*) AS bills_issued_in_window, ROUND(SUM(b.total_amount) / COUNT(*), 2) AS avg_issued_bill
    FROM vendor_bill b
    WHERE b.bill_date >= timestamp '2025-03-01 00:00:00' AND b.bill_date < timestamp '2025-09-01 00:00:00'
    GROUP BY b.vendor_id
)
SELECT c.vendor_id,
       v.name,
       p.avg_issued_bill                                    AS avg_issued_bill_prior_year,
       c.avg_issued_bill                                    AS avg_issued_bill_current,
       ROUND(100 * (c.avg_issued_bill - p.avg_issued_bill) / NULLIF(p.avg_issued_bill, 0), 2) AS yoy_increase_pct,
       (100 * (c.avg_issued_bill - p.avg_issued_bill) / NULLIF(p.avg_issued_bill, 0)) > 10    AS exceeds_10_pct
FROM cur c
JOIN prior p ON p.vendor_id = c.vendor_id
LEFT JOIN ap_vendor v ON v.vendor_id = c.vendor_id
ORDER BY yoy_increase_pct DESC NULLS LAST, c.vendor_id;
