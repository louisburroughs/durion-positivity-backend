-- Ground truth for gate question Q21 (#1689 band 4, cross-domain joins):
--   "Which vendors do we still owe money to, and do any of them have purchase orders
--    still open with us?"
--
-- This is the cross-domain example #1689 names explicitly and the corpus had no coverage of:
-- accounts payable (pos_accounting_db) joined by the reader to purchasing (pos_order_db).
-- There is no cross-database join here and there must not be — no cross-service FKs
-- (CLAUDE.md). The two sections are resolved independently and intersected on supplierId,
-- which is exactly the two-call composition the assistant has to perform (cf. #1676, where
-- it declined one).
--
-- Serving endpoints: AccountingFacadeTool vendor-bill/AP read for the owed side, then the
-- purchase-order search for the open-PO side, per vendor.
--
-- SEMANTICS:
--   Section 1 (pos_accounting_db): "still owe" = vendor bills not yet PAID. VendorBillStatus
--   PAID is terminal-settled; APPROVED is an accepted bill awaiting payment. DRAFT is not an
--   obligation yet and CANCELLED is not one any more, so both are excluded — a bill we have
--   not accepted is not money we owe.
--   Section 2 (pos_order_db): "still open with us" = purchase orders in APPROVED, i.e. issued
--   to the vendor and not yet fully received. FULLY_RECEIVED is closed, DRAFT was never sent,
--   CANCELLED is void.
--
-- The answer is the intersection by supplier_id. A vendor owed money with no open PO is a
-- payment question; a vendor with an open PO and nothing owed is a receiving question. Only
-- the intersection is the exposure the question asks about.

-- DB: pos_accounting_db
\if :{?as_of_date}
\else
\set as_of_date '2026-09-01'
\endif

SELECT
    supplier_id,
    count(*)                AS unpaid_bills,
    sum(total_amount)       AS amount_owed,
    min(due_date)           AS earliest_due,
    max(due_date)           AS latest_due
FROM vendor_bill
WHERE status = 'APPROVED'
GROUP BY supplier_id
ORDER BY amount_owed DESC;

-- Totals, so the per-vendor rows can be cross-footed.
SELECT
    count(*)          AS unpaid_bill_count,
    sum(total_amount) AS total_owed
FROM vendor_bill
WHERE status = 'APPROVED';

-- DB: pos_order_db

SELECT
    supplier_id,
    count(*) AS open_purchase_orders
FROM purchase_order
WHERE status = 'APPROVED'
GROUP BY supplier_id
ORDER BY open_purchase_orders DESC;

SELECT
    status,
    count(*) AS purchase_orders
FROM purchase_order
GROUP BY status
ORDER BY purchase_orders DESC;
