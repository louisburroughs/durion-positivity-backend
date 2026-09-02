-- Ground truth for gate question Q9 (analytics-capability-plan.md §6; tool-selection fixture
-- q09-top-customers-revenue-balance-days-to-pay):
--   "For our top 20 customers, show revenue, invoice count, average invoice value,
--    outstanding balance and average days to pay."
--
-- Serving endpoints: E1 revenue-by-customer (pos-invoice) + aged-receivables (pos-accounting)
--   + E10 payment-application list (pos-accounting; days-to-pay derived by the model).
--   Budget (§6): 4. Tolerance (§2.1): exact for currency/counts; avg invoice value and avg
--   days-to-pay are derived ratios (±0.5 %). Window: last 12 months against EVAL_AS_OF
--   2026-09-01 = 2025-09-01..2026-08-31; balances as of 2026-09-01.
--
-- SEMANTICS:
--   Section 1 (pos_invoice_db) = E1 exactly (see q07 header): FINALIZED/POSTED,
--   deposit-take excluded, window on created_at, top 20 by revenue.
--   Section 2 (pos_accounting_db):
--   * outstanding_balance mirrors generateAgedReceivables/InvoiceBalanceCalculator
--     (q13 header rules): per-customer SUM of positive CURRENT open balances over
--     FINALIZED/POSTED ext_invoice rows whose document date <= as-of.
--   * avg_days_to_pay is the model-derived figure from the E10 list: for each invoice
--     FINALIZED in the window that ever reached zero balance, the lag in fractional days
--     from ext_invoice.finalized_at to its FIRST zero-balance payment_application
--     (application_timestamp; the same "first zero-balance" anchor E3 uses). Averaged per
--     customer; invoices that never hit zero are excluded (they have no days-to-pay yet).
--     This is a SPECIFICATION choice for the gate — E10 itself returns the raw list and the
--     model does this arithmetic; ±0.5 % tolerance applies.
--   The final answer joins the two sections on customer id (in the model's context, not SQL —
--   no cross-database join exists).
--
-- DIVERGENCE (inherited): C2's outstanding balance includes the settlement invoice at
--   1000.00 open although economically 500.00 (deposit draw-downs invisible to
--   InvoiceBalanceCalculator — DATASET.md deposit-pair section).
--
-- Usage: run via run_ground_truth.sh.
-- DB: pos_invoice_db
WITH win AS (
    SELECT date '2025-09-01' AS win_start, date '2026-08-31' AS win_end
)
SELECT
    i.customer_id,
    cp.display_name                          AS name,
    SUM(i.total_amount)                      AS revenue,
    COUNT(*)                                 AS invoice_count,
    ROUND(SUM(i.total_amount) / COUNT(*), 4) AS avg_invoice_value,
    MAX(i.created_at)                        AS last_invoice_date
FROM win w
JOIN invoices i
  ON i.created_at >= (w.win_start::timestamp AT TIME ZONE 'UTC')
 AND i.created_at <  ((w.win_end + 1)::timestamp AT TIME ZONE 'UTC')
LEFT JOIN ext_customer_party cp ON cp.party_id::text = i.customer_id
WHERE i.customer_id IS NOT NULL
  AND i.status IN ('FINALIZED', 'POSTED')
  AND i.deposit_source_type IS NULL
GROUP BY i.customer_id, cp.display_name
ORDER BY revenue DESC, i.customer_id
LIMIT 20;

-- DB: pos_accounting_db
\if :{?as_of_date}
\else
\set as_of_date '''2026-09-01'''
\endif
WITH params AS (
    SELECT CAST(:as_of_date AS date) AS as_of_date,
           date '2025-09-01' AS win_start, date '2026-08-31' AS win_end
),
open_balances AS (   -- InvoiceBalanceCalculator.balanceDue per AR-eligible invoice
    SELECT i.party_id,
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
                           AND t.transaction_type = 'APPLICATION'), 0) AS open_balance
    FROM ext_invoice i
    WHERE i.status IN ('FINALIZED', 'POSTED')
),
balances AS (
    SELECT b.party_id AS customer_id, SUM(b.open_balance) AS outstanding_balance
    FROM open_balances b CROSS JOIN params p
    WHERE b.open_balance > 0 AND b.document_date <= p.as_of_date
    GROUP BY b.party_id
),
paid_lags AS (       -- first zero-balance application per invoice finalized in the window
    SELECT i.party_id AS customer_id,
           EXTRACT(EPOCH FROM (
               (SELECT MIN(pa.application_timestamp) FROM payment_application pa
                WHERE pa.invoice_id = i.invoice_id AND pa.invoice_balance_after = 0)
               - i.finalized_at)) / 86400.0 AS lag_days
    FROM ext_invoice i CROSS JOIN params p
    WHERE i.finalized_at >= (p.win_start::timestamp AT TIME ZONE 'UTC')
      AND i.finalized_at <  ((p.win_end + 1)::timestamp AT TIME ZONE 'UTC')
),
lags AS (
    SELECT customer_id,
           ROUND(AVG(lag_days)::numeric, 2) AS avg_days_to_pay,
           COUNT(lag_days)                  AS paid_invoice_count
    FROM paid_lags
    GROUP BY customer_id
)
SELECT
    COALESCE(b.customer_id, l.customer_id) AS customer_id,
    b.outstanding_balance,
    l.avg_days_to_pay,
    l.paid_invoice_count
FROM balances b
FULL OUTER JOIN lags l ON l.customer_id = b.customer_id
ORDER BY b.outstanding_balance DESC NULLS LAST, 1;
