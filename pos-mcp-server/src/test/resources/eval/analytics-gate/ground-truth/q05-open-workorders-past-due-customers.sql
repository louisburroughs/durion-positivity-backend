-- Ground truth for gate question Q5 (analytics-capability-plan.md §6; tool-selection fixture
-- q05-open-workorders-for-past-due-customers):
--   "Which customers are more than 60 days past due, and what open work orders do we
--    currently have for them?"
--
-- Serving endpoints: AccountingFacadeTool.getAgedReceivables(asOfDate) —
--   GET /v1/accounting/reports/financial/aged-receivables — to find the 60+ customers, then
--   E12 workorder search — GET /v1/workorders/search?customerId=&status=... (pos-workorder) —
--   per customer. Budget (§6): 4. Tolerance (§2.1): exact.
--   asOfDate = EVAL_AS_OF 2026-09-01.
--
-- SEMANTICS:
--   Section 1 (pos_accounting_db) mirrors FinancialReportingServiceImpl.generateAgedReceivables
--   + InvoiceBalanceCalculator.balanceDue (same rules as q13-ar-pareto.sql: due-date aging
--   basis #1604, document-date existence filter, CURRENT open balances). "More than 60 days
--   past due" = days_61_90 + days_90_plus > 0.
--   Section 2 (pos_workorder_db) lists every workorder in a non-terminal status ("open" =
--   NOT IN (COMPLETED, CANCELLED), WorkorderStatus.getOpenStatuses()). E12's status parameter
--   filters ONE status per call, so the model either loops statuses or filters client-side;
--   either way the answer set is section 2 restricted to section 1's customers.
--   Databases cannot be joined (no cross-service FKs) — the COMPOSED answer is:
--   open workorders whose customer_id appears in section 1's 60+ rows. Against the TRACKB
--   seed those are the C1 and C2 rows; the C3 APPROVED row is the designed decoy and MUST
--   NOT appear in the final answer.
--
-- Fixed by #1652: InvoiceBalanceCalculator.balanceDue now subtracts deposit-credit draw-downs
--   (ext_invoice_deposit_credit_application), so C2's settlement invoice reports its true
--   500.00 economic balance in "current", not 1000.00. Does not change the 60+ customer set.
--
-- Usage: run via run_ground_truth.sh (sections run against different databases).
-- DB: pos_accounting_db
\if :{?as_of_date}
\else
\set as_of_date '''2026-09-01'''
\endif
WITH params AS (
    SELECT CAST(:as_of_date AS date) AS as_of_date
),
ar_invoices AS (
    SELECT i.invoice_id,
           CAST(i.party_id AS uuid) AS customer_id,
           COALESCE(i.total, 0) AS total,
           CAST(COALESCE(i.invoice_created_at, i.finalized_at, i.updated_at)
                AT TIME ZONE 'UTC' AS date) AS document_date,
           COALESCE(i.due_date,
                    CAST(COALESCE(i.invoice_created_at, i.finalized_at, i.updated_at)
                         AT TIME ZONE 'UTC' AS date)) AS aging_date
    FROM ext_invoice i
    WHERE i.status IN ('FINALIZED', 'POSTED')
      AND i.party_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
),
open_balances AS (
    SELECT a.*,
           a.total
             - COALESCE((SELECT SUM(pa.applied_amount) FROM payment_application pa
                         WHERE pa.invoice_id = a.invoice_id), 0)
             + COALESCE((SELECT SUM(r.amount) FROM payment_application_reversal r
                         JOIN payment_application pa2
                           ON pa2.payment_application_id = r.original_payment_application_id
                         WHERE pa2.invoice_id = a.invoice_id), 0)
             - COALESCE((SELECT SUM(cm.credit_amount + cm.tax_amount_reversed) FROM credit_memo cm
                         WHERE cm.original_invoice_id = a.invoice_id AND cm.status = 'POSTED'), 0)
             - COALESCE((SELECT SUM(t.amount) FROM customer_credit_transaction t
                         WHERE t.invoice_id = a.invoice_id
                           AND t.transaction_type = 'APPLICATION'), 0)
             -- Deposit-credit draw-downs (#1652) — mirrors InvoiceBalanceCalculator.balanceDue.
             - COALESCE((SELECT SUM(d.amount_applied) FROM ext_invoice_deposit_credit_application d
                         WHERE d.invoice_id = a.invoice_id), 0) AS open_balance
    FROM ar_invoices a
),
aged AS (
    SELECT b.customer_id, b.open_balance,
           (p.as_of_date - b.aging_date) AS days_past_due
    FROM open_balances b CROSS JOIN params p
    WHERE b.open_balance > 0 AND b.document_date <= p.as_of_date
)
SELECT customer_id,
       COALESCE(SUM(open_balance) FILTER (WHERE days_past_due <= 30), 0)                          AS current_bucket,
       COALESCE(SUM(open_balance) FILTER (WHERE days_past_due > 30 AND days_past_due <= 60), 0)   AS days_31_60,
       COALESCE(SUM(open_balance) FILTER (WHERE days_past_due > 60 AND days_past_due <= 90), 0)   AS days_61_90,
       COALESCE(SUM(open_balance) FILTER (WHERE days_past_due > 90), 0)                           AS days_90_plus,
       SUM(open_balance)                                                                          AS total_outstanding
FROM aged
GROUP BY customer_id
HAVING COALESCE(SUM(open_balance) FILTER (WHERE days_past_due > 60), 0) > 0
ORDER BY total_outstanding DESC;

-- DB: pos_workorder_db
-- Every open (non-terminal) workorder, with its customer. The composed Q5 answer keeps only
-- the rows whose customer_id matched section 1 (TRACKB design: the two C1 WOs + the C2 WO;
-- the C3 APPROVED row is the decoy that must be dropped).
SELECT w.id AS workorder_id,
       w.workorder_number,
       w.status,
       w.customer_id,
       cp.display_name AS customer_name,
       w.created_at
FROM workorder w
LEFT JOIN ext_customer_party cp ON cp.party_id = w.customer_id
WHERE w.status NOT IN ('COMPLETED', 'CANCELLED')
ORDER BY w.customer_id, w.created_at;
