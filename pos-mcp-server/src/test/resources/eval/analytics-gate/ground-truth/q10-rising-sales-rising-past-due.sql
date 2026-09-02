-- Ground truth for gate question Q10 (analytics-capability-plan.md §6; tool-selection fixture
-- q10-rising-sales-rising-past-due):
--   "Which customers have rising sales but also rising past-due balances over the last
--    three months?"
--
-- Serving endpoints: 3 × E1 revenue-by-customer (pos-invoice) + 3 × aged-receivables
--   (pos-accounting) — Wave 2 path; 2 calls in Wave 3. Budget (§6): 7 (W3: 2).
--   Tolerance (§2.1): exact currency. Months against EVAL_AS_OF 2026-09-01:
--   2026-06, 2026-07, 2026-08 (aging as-of each month-end).
--
-- SEMANTICS:
--   Section 1 (pos_invoice_db): E1 per calendar month (q07 header rules — FINALIZED/POSTED,
--   deposit-take excluded, created_at window).
--   Section 2 (pos_accounting_db): the aged-receivables ENDPOINT evaluated at each month-end
--   as-of date. Per generateAgedReceivables this uses each invoice's CURRENT open balance
--   (all applications/reversals/credit-memos to date) against historical bucket boundaries,
--   and excludes invoices whose document date is after the as-of — it is NOT a point-in-time
--   balance. past_due = days31To60 + days61To90 + days90Plus (due-date basis, #1604).
--
-- DIVERGENCE (the reason Q10 is a Wave 3 gate, plan §W3.1): a true past-due TREND needs
--   point-in-time balance reconstruction, which no endpoint produces today. Section 2 is the
--   truth for what the TOOL returns when looped over historical as-of dates — the honest
--   Wave 2 answer must present it with that caveat; the economically-true trend is BLOCKED
--   (see EXPECTED.md). Also inherited: C2's settlement invoice reports 1000.00 open though
--   economically 500.00 (deposit draw-down invisible to InvoiceBalanceCalculator).
--
-- Usage: run via run_ground_truth.sh.
-- DB: pos_invoice_db
WITH months AS (
    SELECT generate_series(date '2026-06-01', date '2026-08-01', interval '1 month')::date AS month_start
)
SELECT
    m.month_start,
    i.customer_id,
    cp.display_name     AS name,
    SUM(i.total_amount) AS revenue,
    COUNT(*)            AS invoice_count
FROM months m
JOIN invoices i
  ON i.created_at >= (m.month_start::timestamp AT TIME ZONE 'UTC')
 AND i.created_at <  ((m.month_start + interval '1 month')::timestamp AT TIME ZONE 'UTC')
LEFT JOIN ext_customer_party cp ON cp.party_id::text = i.customer_id
WHERE i.customer_id IS NOT NULL
  AND i.status IN ('FINALIZED', 'POSTED')
  AND i.deposit_source_type IS NULL
GROUP BY m.month_start, i.customer_id, cp.display_name
ORDER BY m.month_start, revenue DESC, i.customer_id;

-- DB: pos_accounting_db
-- Aged-receivables ENDPOINT figures at each month-end (current balances — see DIVERGENCE).
WITH asofs AS (
    SELECT unnest(ARRAY[date '2026-06-30', date '2026-07-31', date '2026-08-31']) AS as_of_date
),
open_balances AS (
    SELECT i.invoice_id,
           CAST(i.party_id AS uuid) AS customer_id,
           CAST(COALESCE(i.invoice_created_at, i.finalized_at, i.updated_at)
                AT TIME ZONE 'UTC' AS date) AS document_date,
           COALESCE(i.due_date,
                    CAST(COALESCE(i.invoice_created_at, i.finalized_at, i.updated_at)
                         AT TIME ZONE 'UTC' AS date)) AS aging_date,
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
      AND i.party_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
)
SELECT
    a.as_of_date,
    b.customer_id,
    COALESCE(SUM(b.open_balance) FILTER (WHERE (a.as_of_date - b.aging_date) <= 30), 0) AS current_bucket,
    COALESCE(SUM(b.open_balance) FILTER (WHERE (a.as_of_date - b.aging_date) > 30), 0)  AS past_due,
    SUM(b.open_balance)                                                                 AS total_outstanding
FROM asofs a
JOIN open_balances b
  ON b.open_balance > 0 AND b.document_date <= a.as_of_date
GROUP BY a.as_of_date, b.customer_id
ORDER BY a.as_of_date, b.customer_id;
