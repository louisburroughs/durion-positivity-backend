-- Issue #2309 (ADR-0067 DF-1, PC-9, PC-13): a vendor bill keeps the currency its supplier invoice
-- states, and a bill in a currency other than the ledger's is held in the new CURRENCY_HOLD status
-- instead of being booked at par. The column is nullable: bills recorded before it, and bills from
-- sources that state no currency, mean the ledger currency (ADR-0067 E-3). No backfill here; the
-- Stage A USD backfill runs after the OP-10 data check.
ALTER TABLE vendor_bill ADD COLUMN currency character varying(3);

ALTER TABLE vendor_bill DROP CONSTRAINT vendor_bill_status_check;

ALTER TABLE vendor_bill ADD CONSTRAINT vendor_bill_status_check CHECK (
    status IN ('PENDING_RECEIPT_MATCH', 'MATCH_EXCEPTION', 'CURRENCY_HOLD', 'APPROVED', 'REJECTED', 'PAID', 'VOIDED'));
