-- Ground truth for gate question Q7 (analytics-capability-plan.md §6; tool-selection fixture
-- q07-top-customers-year-over-year):
--   "Who were our top customers by revenue over the last twelve months compared with the
--    twelve months before that?"
--
-- Serving endpoint: E1 — GET /v1/invoices/analytics/revenue-by-customer?startDate=&endDate=&limit=
--   (pos-invoice), one call per window. Budget (§6): 3. Tolerance (§2.1): exact currency;
--   avgInvoiceValue is a derived ratio (±0.5 %). Windows against EVAL_AS_OF 2026-09-01:
--   2025-09-01..2026-08-31 vs 2024-09-01..2025-08-31 (the DATASET.md E1 windows).
--
-- SEMANTICS — mirrors InvoiceAnalyticsServiceImpl.revenueByCustomer /
--   InvoiceRepository.revenueByCustomer exactly:
--   * Universe: invoices with customer_id NOT NULL, status IN (FINALIZED, POSTED) — DRAFT is
--     unbilled, CANCELLED/ERROR never will be — AND deposit_source_type IS NULL (#1623/D8:
--     the deposit-take document is a contract liability, not a sale).
--   * Window on invoices.created_at in [start 00:00Z, end 23:59:59.999999Z].
--   * revenue = SUM(total_amount); invoiceCount = COUNT(*); avgInvoiceValue = revenue/count
--     at scale 4 HALF_UP; lastInvoiceDate = MAX(created_at) WITHIN the window.
--   * Ordered revenue DESC; limit defaults high enough that no truncation occurs (6 customers).
--   Customer display names are decoration from the same database's ext_customer_party replica
--   (the endpoint resolves names the same way via CustomerReferenceService).
--
-- Usage: psql -f q07-... <pos_invoice_db>
-- DB: pos_invoice_db
WITH windows AS (
    SELECT 'last-12mo'  AS window_label, date '2025-09-01' AS win_start, date '2026-08-31' AS win_end
    UNION ALL
    SELECT 'prior-12mo', date '2024-09-01', date '2025-08-31'
)
SELECT
    w.window_label,
    i.customer_id,
    cp.display_name                                             AS name,
    SUM(i.total_amount)                                         AS revenue,
    COUNT(*)                                                    AS invoice_count,
    ROUND(SUM(i.total_amount) / COUNT(*), 4)                    AS avg_invoice_value,
    MAX(i.created_at)                                           AS last_invoice_date
FROM windows w
JOIN invoices i
  ON i.created_at >= (w.win_start::timestamp AT TIME ZONE 'UTC')
 AND i.created_at <  ((w.win_end + 1)::timestamp AT TIME ZONE 'UTC')
LEFT JOIN ext_customer_party cp ON cp.party_id::text = i.customer_id
WHERE i.customer_id IS NOT NULL
  AND i.status IN ('FINALIZED', 'POSTED')
  AND i.deposit_source_type IS NULL
GROUP BY w.window_label, i.customer_id, cp.display_name
ORDER BY w.window_label, revenue DESC, i.customer_id;
