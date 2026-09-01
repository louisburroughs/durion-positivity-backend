-- #1623: mark deposit-take invoices on the invoice document itself.
-- Accounting ruling: the invoice a deposit-take order renders for the down-payment is a contract
-- liability (unearned revenue), not a sale — revenue-shaped measures summing invoice totals
-- (E1 revenue-by-customer, E2 invoiced) must exclude it, or a $500 deposit on a $2,000 job
-- reports $2,500 invoiced across the take and settlement windows. Provenance so far lived only
-- on deposit_credit; stamping it here lets analytics queries (and the invoice.invoice.updated
-- event feeding accounting's ext_invoice replica) filter without joining the credit artifact.

ALTER TABLE invoices ADD COLUMN deposit_source_type VARCHAR(16);
ALTER TABLE invoices ADD COLUMN deposit_source_id UUID;

-- Backfill from the credit artifact: deposit_credit.order_id is the taking order (unique), and
-- the from-order path creates exactly one invoice per order, so the join identifies precisely
-- the historical deposit-take invoices. The workorder-dedupe path (spec R7.2) returns before a
-- credit is registered, so no settlement invoice can match.
UPDATE invoices i
SET deposit_source_type = dc.source_type,
    deposit_source_id = dc.source_id
FROM deposit_credit dc
WHERE dc.order_id = i.order_id;
