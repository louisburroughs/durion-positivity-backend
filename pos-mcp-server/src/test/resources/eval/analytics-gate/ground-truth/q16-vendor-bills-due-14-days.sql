-- Ground truth for gate question Q16 (analytics-capability-plan.md §6):
--   "Vendor bills due ≤ 14 days, daily cash need" — which vendor bills are due within the
--   next 14 days, and how much cash is needed on each day?
--
-- Serving endpoints: E9 — GET /v1/accounting/vendor-bills?dueFrom=&dueTo=&status= — and/or
--   AccountingFacadeTool.getAgedPayables(asOfDate). Budget (§6): 2. Tolerance (§2.1): exact.
--   Window against EVAL_AS_OF 2026-09-01: due 2026-09-01..2026-09-15 (14 days inclusive).
--
-- SEMANTICS:
--   Result 1 mirrors VendorBillServiceImpl.listByDueDateWindow (E9): vendor_bill rows with
--   due_date (naive LocalDateTime) in [dueFrom 00:00, dueTo 23:59:59.999999], optional
--   status filter (none here — the model filters unpaid in context or passes
--   status=APPROVED), ordered dueDate ASC. Row amount = total_amount, the GROSS bill amount
--   (E9 exposes no open balance; every Q16 bill is fully unpaid so gross == open).
--   Result 2 is the model's derived daily cash-need bucketing (due day -> sum), which
--   criterion 1 checks against the answer.
--   Result 3 mirrors generateAgedPayables at the as-of: open-payable statuses
--   (PENDING_RECEIPT_MATCH, MATCH_EXCEPTION, APPROVED), open balance = total_amount minus
--   ap_payment_allocation sums, existence-filtered on bill_date, aged by due_date (fallback
--   bill_date); not-yet-due lands in current (#1604).
--
-- Usage: psql -v due_from="'2026-09-01'" -v due_to="'2026-09-15'" -f q16-... <pos_accounting_db>
-- DB: pos_accounting_db
\if :{?due_from}
\else
\set due_from '''2026-09-01'''
\endif
\if :{?due_to}
\else
\set due_to '''2026-09-15'''
\endif
WITH params AS (
    SELECT CAST(:due_from AS date) AS due_from, CAST(:due_to AS date) AS due_to
)
SELECT b.vendor_bill_id AS bill_id, b.vendor_id, v.name AS vendor_name, b.bill_number,
       b.due_date, b.total_amount AS amount, b.status
FROM vendor_bill b
CROSS JOIN params p
LEFT JOIN ap_vendor v ON v.vendor_id = b.vendor_id
WHERE b.due_date >= p.due_from::timestamp
  AND b.due_date <  (p.due_to + 1)::timestamp
ORDER BY b.due_date, b.vendor_bill_id;

-- Derived daily cash need (model arithmetic over the E9 rows).
WITH params AS (
    SELECT CAST(:due_from AS date) AS due_from, CAST(:due_to AS date) AS due_to
)
SELECT CAST(b.due_date AS date) AS due_day, SUM(b.total_amount) AS cash_needed
FROM vendor_bill b CROSS JOIN params p
WHERE b.due_date >= p.due_from::timestamp
  AND b.due_date <  (p.due_to + 1)::timestamp
GROUP BY CAST(b.due_date AS date)
ORDER BY due_day;

-- Aged payables at the as-of date (generateAgedPayables mirror; all four TRACKB bills land
-- in `current` — due in the future, included since #1604).
WITH params AS (
    SELECT CAST(:due_from AS date) AS as_of_date   -- as-of = window start = EVAL_AS_OF
),
open_bills AS (
    SELECT b.vendor_id, b.vendor_name,
           CAST(b.bill_date AS date) AS document_date,
           CAST(COALESCE(b.due_date, b.bill_date) AS date) AS aging_date,
           COALESCE(b.total_amount, 0)
             - COALESCE((SELECT SUM(a.applied_amount) FROM ap_payment_allocation a
                         WHERE a.vendor_bill_id = b.vendor_bill_id), 0) AS open_balance
    FROM vendor_bill b
    WHERE b.status IN ('PENDING_RECEIPT_MATCH', 'MATCH_EXCEPTION', 'APPROVED')
)
SELECT o.vendor_id, o.vendor_name,
       COALESCE(SUM(o.open_balance) FILTER (WHERE (p.as_of_date - o.aging_date) <= 30), 0)                            AS current_bucket,
       COALESCE(SUM(o.open_balance) FILTER (WHERE (p.as_of_date - o.aging_date) > 30 AND (p.as_of_date - o.aging_date) <= 60), 0) AS days_31_60,
       COALESCE(SUM(o.open_balance) FILTER (WHERE (p.as_of_date - o.aging_date) > 60 AND (p.as_of_date - o.aging_date) <= 90), 0) AS days_61_90,
       COALESCE(SUM(o.open_balance) FILTER (WHERE (p.as_of_date - o.aging_date) > 90), 0)                             AS days_90_plus,
       SUM(o.open_balance)                                                                                            AS total_outstanding
FROM open_bills o CROSS JOIN params p
WHERE o.open_balance > 0 AND o.document_date <= p.as_of_date
GROUP BY o.vendor_id, o.vendor_name
ORDER BY o.vendor_name, o.vendor_id;
