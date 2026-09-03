-- Ground truth for gate question Q13 (analytics-capability-plan.md §6):
--   "Which customers make up most of our accounts receivable balance, and how much of
--    each is past due?"
--
-- Wave 1 gate recorded a full pass for Q13; that run predates issue #1604 and its figures are NOT
-- comparable to what this script now specifies (see docs/gate-runs/2026-09-01-ar-aging-basis-
-- change.md). The chat answer is produced from ONE tool call —
-- AccountingFacadeTool.getAgedReceivables(asOfDate) → GET /v1/accounting/reports/financial/
-- aged-receivables?asOfDate= — with the model doing the Pareto in-context. This script is the
-- specification of the expected figures (plan §2.1 criterion 1: exact for currency, ±0.5 % for
-- derived ratios).
--
-- Schema: pos-accounting ONLY. A/R aging reads pos-accounting's event-fed invoice replica
-- (ext_invoice) plus accounting's own cash/credit facts — never pos-invoice's `invoices` table.
-- Do not add a cross-schema join; there are no cross-service foreign keys.
--
-- The bucketing below mirrors FinancialReportingServiceImpl.generateAgedReceivables and
-- InvoiceBalanceCalculator.balanceDue exactly. Three behaviours are easy to get wrong:
--   1. The aging date is the invoice's due date, falling back to the document date
--      COALESCE(invoice_created_at, finalized_at, updated_at) when due_date is null (drafts, and
--      replica rows built from events predating V22__ext_invoice_due_date.sql). This is the same
--      rule the A/P side applies, so the two reports' "past due" axes are the same measure.
--      Corrected by issue #1604; before that fix A/R aged from the document date alone.
--   2. Buckets are inclusive upper bounds on days past due: <=30 / <=60 / <=90 / >90. A not-yet-due
--      invoice has days_past_due < 0, which satisfies <=30 and so lands in "current" — it is
--      INCLUDED, not dropped. What is excluded is an invoice that did not yet exist as of
--      :as_of_date, tested on its DOCUMENT date (never on the aging date).
--   3. The open balance is the invoice's CURRENT balance, so a back-dated :as_of_date does NOT
--      reconstruct a point-in-time balance (known limitation, documented in the service). Run
--      this script with the same :as_of_date the gate run used.
--
-- AUDIT 2026-09-02 (Track B ground-truth suite): re-verified clause-by-clause against the
-- current FinancialReportingServiceImpl.generateAgedReceivables / receivableAgingDate /
-- receivableDocumentDate and InvoiceBalanceCalculator.balanceDue — no drift; the post-#1604
-- due-date basis below is what the service ships today. Only change: the default as_of_date
-- now matches the TRACKB seed's EVAL_AS_OF (2026-09-01). Budget (§6): 2 tool calls;
-- tolerance (§2.1): exact currency, ±0.5 % on the derived shares.
--
-- Usage:  psql -v as_of_date="'2026-09-01'" -f q13-ar-pareto.sql <pos_accounting_db>
-- DB: pos_accounting_db

\if :{?as_of_date}
\else
\set as_of_date '''2026-09-01'''
\endif

WITH params AS (
    SELECT CAST(:as_of_date AS date) AS as_of_date
),

-- AR-eligible replica rows. AR_ELIGIBLE_STATUSES = {FINALIZED, POSTED}; party_id is a
-- varchar(64) on the replica and a non-UUID party is skipped by the service, so filter the
-- same way rather than letting a cast fail the whole report.
ar_invoices AS (
    SELECT
        i.invoice_id,
        CAST(i.party_id AS uuid) AS customer_id,
        COALESCE(i.total, 0) AS total,
        -- Document date ("did this invoice exist yet?"). Instants are read at UTC for
        -- timezone-independent day math, matching receivableDocumentDate.
        CAST(COALESCE(i.invoice_created_at, i.finalized_at, i.updated_at)
             AT TIME ZONE 'UTC' AS date) AS document_date,
        -- Aging basis ("how far past due is it?") = due_date, else the document date.
        -- ext_invoice.due_date is already a `date`; only the timestamp branch is converted, so
        -- both COALESCE arms are `date` and neither wraps a date in AT TIME ZONE.
        COALESCE(
            i.due_date,
            CAST(COALESCE(i.invoice_created_at, i.finalized_at, i.updated_at)
                 AT TIME ZONE 'UTC' AS date)
        ) AS aging_date
    FROM ext_invoice i
    WHERE i.status IN ('FINALIZED', 'POSTED')
      AND i.party_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
),

-- InvoiceBalanceCalculator.balanceDue:
--   total - applied + reversed - credited(POSTED) - customer credit APPLICATIONs
--   - deposit credit applications (#1652)
open_balances AS (
    SELECT
        a.invoice_id,
        a.customer_id,
        a.document_date,
        a.aging_date,
        a.total
            - COALESCE((SELECT SUM(pa.applied_amount)
                        FROM payment_application pa
                        WHERE pa.invoice_id = a.invoice_id), 0)
            + COALESCE((SELECT SUM(r.amount)
                        FROM payment_application_reversal r
                        JOIN payment_application pa2
                          ON pa2.payment_application_id = r.original_payment_application_id
                        WHERE pa2.invoice_id = a.invoice_id), 0)
            - COALESCE((SELECT SUM(cm.credit_amount + cm.tax_amount_reversed)
                        FROM credit_memo cm
                        WHERE cm.original_invoice_id = a.invoice_id
                          AND cm.status = 'POSTED'), 0)
            - COALESCE((SELECT SUM(t.amount)
                        FROM customer_credit_transaction t
                        WHERE t.invoice_id = a.invoice_id
                          AND t.transaction_type = 'APPLICATION'), 0)
            -- Deposit-credit draw-downs (#1652) — mirrors InvoiceBalanceCalculator.balanceDue.
            - COALESCE((SELECT SUM(d.amount_applied)
                        FROM ext_invoice_deposit_credit_application d
                        WHERE d.invoice_id = a.invoice_id), 0)
            AS open_balance
    FROM ar_invoices a
),

aged AS (
    SELECT
        b.customer_id,
        b.open_balance,
        -- Negative for a not-yet-due invoice; kept, and bucketed as "current" below.
        (p.as_of_date - b.aging_date) AS days_past_due
    FROM open_balances b
    CROSS JOIN params p
    WHERE b.open_balance > 0                    -- only positive-open items contribute
      -- Existence filter, on the DOCUMENT date: an invoice raised after as_of_date did not exist
      -- yet. NOT on the aging date — a not-yet-due invoice already exists and is still reported.
      AND b.document_date <= p.as_of_date
),

-- One row per customer: the AgedReceivablesRow contract (customerName is deliberately absent —
-- the service emits NULL for it, "no directory lookup in this slice", so ground truth keys on id).
rows_by_customer AS (
    SELECT
        customer_id,
        -- <= 30 also catches every negative days_past_due, i.e. everything not yet due.
        SUM(open_balance) FILTER (WHERE days_past_due <= 30) AS current_bucket,
        SUM(open_balance) FILTER (WHERE days_past_due > 30 AND days_past_due <= 60) AS days_31_60,
        SUM(open_balance) FILTER (WHERE days_past_due > 60 AND days_past_due <= 90) AS days_61_90,
        SUM(open_balance) FILTER (WHERE days_past_due > 90) AS days_90_plus,
        SUM(open_balance) AS total_outstanding
    FROM aged
    GROUP BY customer_id
),

normalized AS (
    SELECT
        customer_id,
        COALESCE(current_bucket, 0) AS current_bucket,
        COALESCE(days_31_60, 0) AS days_31_60,
        COALESCE(days_61_90, 0) AS days_61_90,
        COALESCE(days_90_plus, 0) AS days_90_plus,
        total_outstanding,
        -- "Past due" for Q13 = anything beyond the current bucket, i.e. more than 30 days past
        -- the invoice's due date. Since #1604 that is literally what it says: the current bucket
        -- holds the not-yet-due and up-to-30-days-late money, and these three hold overdue money.
        COALESCE(days_31_60, 0) + COALESCE(days_61_90, 0) + COALESCE(days_90_plus, 0) AS past_due
    FROM rows_by_customer
),

ranked AS (
    SELECT
        n.*,
        SUM(n.total_outstanding) OVER () AS grand_total,
        ROW_NUMBER() OVER (ORDER BY n.total_outstanding DESC, n.customer_id) AS rank_position,
        SUM(n.total_outstanding) OVER (
            ORDER BY n.total_outstanding DESC, n.customer_id
            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
        ) AS cumulative_outstanding
    FROM normalized n
)

SELECT
    r.rank_position,
    r.customer_id,
    ROUND(r.total_outstanding, 2) AS total_outstanding,
    ROUND(r.current_bucket, 2) AS current_0_30,
    ROUND(r.days_31_60, 2) AS days_31_60,
    ROUND(r.days_61_90, 2) AS days_61_90,
    ROUND(r.days_90_plus, 2) AS days_90_plus,
    ROUND(r.past_due, 2) AS past_due_amount,
    ROUND(100 * r.past_due / NULLIF(r.total_outstanding, 0), 2) AS past_due_pct_of_customer,
    ROUND(100 * r.total_outstanding / NULLIF(r.grand_total, 0), 2) AS share_of_ar_pct,
    ROUND(100 * r.cumulative_outstanding / NULLIF(r.grand_total, 0), 2) AS cumulative_share_pct,
    -- The Pareto set: every customer up to and INCLUDING the first row whose cumulative share
    -- reaches 80 %. The lag() form is what makes the boundary row itself part of the answer.
    (COALESCE(LAG(r.cumulative_outstanding) OVER (ORDER BY r.rank_position), 0)
        < 0.80 * r.grand_total) AS in_pareto_80
FROM ranked r
ORDER BY r.rank_position;
