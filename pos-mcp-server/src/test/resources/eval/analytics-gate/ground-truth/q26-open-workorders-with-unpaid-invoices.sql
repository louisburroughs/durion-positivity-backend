-- Ground truth for gate question Q26 (#1689 band 4, cross-domain join):
--   "Which customers have an open work order and also owe us money, and how much?"
--
-- Band 4 is the band #1689 calls out by name — "customers with both unpaid invoices and open
-- work orders" — and the one where tool SEQUENCING failures show up (cf. #1676): the answer
-- exists in neither domain alone. The assistant must read open work orders from the workorder
-- domain, open receivables from accounting, and intersect them on customer id. A model that
-- answers from one side produces a longer, plausible list; that is the failure to catch.
--
-- SEMANTICS:
--   Open work order = the six non-terminal statuses WorkorderFacadeTool.searchWorkorders accepts
--   under its "OPEN" alias: APPROVED, ASSIGNED, WORK_IN_PROGRESS, AWAITING_PARTS,
--   AWAITING_APPROVAL, READY_FOR_PICKUP. DRAFT is deliberately NOT open here. That is arguable
--   in business terms — a draft job is on the board — but the ground truth has to mean what the
--   tool means, or a correct answer grades as wrong (the #1659 failure). The seed carries 92
--   DRAFT work orders, so the two readings differ by an order of magnitude: 45 open under the
--   tool's definition, 137 if DRAFT counts. Section 1 reports both, and the second section of
--   the intersection is unaffected — no DRAFT-only customer has an open receivable.
--
--   Owes us money = a positive open balance on a FINALIZED/POSTED invoice, computed exactly as
--   q13 does (InvoiceBalanceCalculator.balanceDue: total - applied + reversed - posted credit
--   memos (credit_amount + tax_amount_reversed) - customer-credit applications - deposit-credit
--   draw-downs). The block is copied from q13 verbatim, column names included: a first draft
--   here retyped it from memory and got payment_application's key and credit_memo's amount
--   column wrong, which psql reported on stderr while the section returned no rows at all.
--   Reusing q13's derivation
--   is deliberate: two questions that disagree about what "owed" means would make a difference
--   in the ANSWER indistinguishable from a difference in the ground truth.
--
--   The two sections run against different databases because the domains own separate schemas
--   and there is no cross-service join anywhere in the platform. The expected answer is their
--   intersection on customer id, computed from the two outputs — the same shape as q21.
--
--   AS-OF: the receivable half takes :as_of_date and excludes invoices whose DOCUMENT date is
--   after it, exactly as q13 and q05 do and exactly as AccountingFacadeTool.getAgedReceivables
--   documents ("invoices raised after that date are excluded entirely"). A first draft omitted
--   both the parameter and the filter while claiming to be q13 verbatim; it was q13's arithmetic
--   with q13's guard rail removed, so a re-measure at another as-of date would have left this
--   question alone while every other AR question moved.
--
--   Point-in-time, so no window is stated or needed: both "open work order" and "owes us money"
--   are current-state questions (cf. b11, where an unstated range must not produce a question).

-- DB: pos_workorder_db
-- Section 1 — customers with at least one open work order, with the count and the statuses.
SELECT
    w.customer_id,
    COUNT(*)                                             AS open_workorders,
    STRING_AGG(DISTINCT w.status, ',' ORDER BY w.status) AS open_statuses
FROM workorder w
WHERE w.status IN ('APPROVED', 'ASSIGNED', 'WORK_IN_PROGRESS',
                   'AWAITING_PARTS', 'AWAITING_APPROVAL', 'READY_FOR_PICKUP')
  AND w.customer_id IS NOT NULL
GROUP BY w.customer_id
ORDER BY open_workorders DESC, w.customer_id;

-- Section 1b — how far the two readings of "open" diverge, and which customers only DRAFT adds.
-- The counts make the divergence visible; the id list makes the invariant checkable against
-- section 2 instead of asserted.
SELECT
    COUNT(*) FILTER (WHERE status <> 'DRAFT')                     AS open_tool_definition,
    COUNT(*)                                                      AS open_including_draft,
    COUNT(DISTINCT customer_id) FILTER (WHERE status <> 'DRAFT')  AS customers_tool_definition,
    COUNT(DISTINCT customer_id)                                   AS customers_including_draft
FROM workorder
WHERE status IN ('APPROVED', 'ASSIGNED', 'WORK_IN_PROGRESS', 'AWAITING_PARTS',
                 'AWAITING_APPROVAL', 'READY_FOR_PICKUP', 'DRAFT')
  AND customer_id IS NOT NULL;

SELECT w.customer_id AS draft_only_customer_id, COUNT(*) AS draft_workorders
FROM workorder w
WHERE w.status = 'DRAFT'
  AND w.customer_id IS NOT NULL
  AND NOT EXISTS (
        SELECT 1 FROM workorder o
        WHERE o.customer_id = w.customer_id
          AND o.status IN ('APPROVED', 'ASSIGNED', 'WORK_IN_PROGRESS',
                           'AWAITING_PARTS', 'AWAITING_APPROVAL', 'READY_FOR_PICKUP'))
GROUP BY w.customer_id
ORDER BY w.customer_id;

-- DB: pos_accounting_db
-- Section 2 — customers with a positive open receivable balance, q13's balanceDue derivation
-- under q13's as-of rule. Usage: psql -v as_of_date="'2026-09-01'" (default below).
\if :{?as_of_date}
\else
\set as_of_date '2026-09-01'
\endif

WITH params AS (
    SELECT DATE :'as_of_date' AS as_of_date
),
ar_invoices AS (
    SELECT
        i.invoice_id,
        CAST(i.party_id AS uuid) AS customer_id,
        COALESCE(i.total, 0)     AS total,
        CAST(COALESCE(i.invoice_created_at, i.finalized_at, i.updated_at)
             AT TIME ZONE 'UTC' AS date) AS document_date
    FROM ext_invoice i
    WHERE i.status IN ('FINALIZED', 'POSTED')
      AND i.party_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
),
open_balances AS (
    SELECT
        a.customer_id,
        a.document_date,
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
            - COALESCE((SELECT SUM(d.amount_applied)
                        FROM ext_invoice_deposit_credit_application d
                        WHERE d.invoice_id = a.invoice_id), 0)
            AS open_balance
    FROM ar_invoices a
)
SELECT
    b.customer_id,
    COUNT(*)            AS unpaid_invoices,
    SUM(b.open_balance) AS amount_owed
FROM open_balances b
CROSS JOIN params p
WHERE b.open_balance > 0
  AND b.document_date <= p.as_of_date   -- q13/q05: an invoice raised after the as-of is not yet AR
GROUP BY b.customer_id
ORDER BY amount_owed DESC, b.customer_id;
