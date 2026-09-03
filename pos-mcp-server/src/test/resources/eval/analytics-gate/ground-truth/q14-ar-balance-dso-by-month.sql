-- Ground truth for gate question Q14 (analytics-capability-plan.md §6; tool-selection fixture
-- q14-ar-balance-and-dso-by-month):
--   "What was our accounts receivable balance and DSO at each month-end for the last
--    12 months?"
--
-- Serving endpoints (as recorded): aged-receivables batch + income statements, DSO in model.
--   Budget (§6): 3. Wave 3, and formally WITHDRAWN at Wave 1 (plan §3): a true A/R balance
--   trend needs point-in-time balance reconstruction (W3.1 prerequisite) which does not exist.
--   Month-ends against EVAL_AS_OF 2026-09-01: 2025-09-30 .. 2026-08-31.
--
-- SEMANTICS — this script computes what the aged-receivables ENDPOINT returns when called
--   with each historical month-end asOfDate (generateAgedReceivables: each invoice's CURRENT
--   open balance, existence-filtered on the document date; q13 header rules). That is the
--   only computable half:
--   * The TRUE point-in-time A/R balance per month-end is BLOCKED (no application replay).
--     The endpoint figure below equals the true figure ONLY at the latest as-of (2026-08-31 /
--     today); for earlier month-ends it reports today's residue of old invoices (early months
--     read 0 because every early invoice is now paid) — see EXPECTED.md Q14.
--   * DSO is BLOCKED twice over: it needs the true balance, and the "income statement"
--     revenue side reads GL journal entries, which the TRACKB seed does not populate
--     (no journal_entry rows are seeded; DATASET.md scope).
--
-- DIVERGENCE: the whole month-end series (except the last point) is the documented
--   known-limitation output, not history; an answer presenting it as a real balance trend
--   fails criterion 4 honesty even if the numbers match this script.
--
-- Usage: psql -f q14-... <pos_accounting_db>
-- DB: pos_accounting_db
WITH month_ends AS (
    SELECT (generate_series(date '2025-10-01', date '2026-09-01', interval '1 month') - interval '1 day')::date
           AS as_of_date
),
open_balances AS (
    SELECT i.invoice_id,
           CAST(COALESCE(i.invoice_created_at, i.finalized_at, i.updated_at)
                AT TIME ZONE 'UTC' AS date) AS document_date,
           COALESCE(i.total, 0)
             - COALESCE((SELECT SUM(pa.applied_amount) FROM payment_application pa
                         WHERE pa.invoice_id = i.invoice_id), 0)
             + COALESCE((SELECT SUM(r.amount) FROM payment_application_reversal r
                         JOIN payment_application pa2
                           ON pa2.payment_application_id = r.original_payment_application_id
                         WHERE pa2.invoice_id = i.invoice_id), 0)
             - COALESCE((SELECT SUM(cm.credit_amount + cm.tax_amount_reversed) FROM credit_memo cm
                         WHERE cm.original_invoice_id = i.invoice_id AND cm.status = 'POSTED'), 0)
             - COALESCE((SELECT SUM(t.amount) FROM customer_credit_transaction t
                         WHERE t.invoice_id = i.invoice_id
                           AND t.transaction_type = 'APPLICATION'), 0)
             -- Deposit-credit draw-downs (#1652) — mirrors InvoiceBalanceCalculator.balanceDue.
             - COALESCE((SELECT SUM(d.amount_applied) FROM ext_invoice_deposit_credit_application d
                         WHERE d.invoice_id = i.invoice_id), 0) AS open_balance
    FROM ext_invoice i
    WHERE i.status IN ('FINALIZED', 'POSTED')
)
SELECT
    m.as_of_date,
    COALESCE(SUM(b.open_balance), 0) AS endpoint_total_outstanding,  -- CURRENT balances; NOT point-in-time
    NULL::numeric                    AS true_point_in_time_balance,  -- BLOCKED (W3.1)
    NULL::numeric                    AS dso                          -- BLOCKED (needs true balance + GL revenue)
FROM month_ends m
LEFT JOIN open_balances b
  ON b.open_balance > 0 AND b.document_date <= m.as_of_date
GROUP BY m.as_of_date
ORDER BY m.as_of_date;
