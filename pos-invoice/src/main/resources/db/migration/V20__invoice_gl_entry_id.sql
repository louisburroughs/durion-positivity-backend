-- #1843: record the revenue journal entry pos-accounting posted for a finalized invoice.
-- Set when the accounting.invoice.gl-posted fact moves the invoice FINALIZED -> POSTED;
-- null for every invoice that has not been posted to the ledger.

SET TIME ZONE 'UTC';

ALTER TABLE invoices ADD COLUMN gl_entry_id uuid;
