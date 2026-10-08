-- CAP:550 S32a (louisburroughs/durion-positivity-backend#2636): each persisted tax row carries the
-- tax-type code pos-tax priced it with (e.g. GST; a configuration-only vocabulary declared per country
-- in pos-tax), copied as received and never inferred. NULL for an untyped row: every existing row is US
-- and stays NULL permanently. No default, no CHECK (the vocabulary is configuration) and no backfill:
-- the finalized-state guard refuses any re-price, so rows freeze at finalization, and a DRAFT invoice
-- picks the type up on its next re-price. Tenant-scoped; RLS unchanged.
ALTER TABLE invoice_line_tax    ADD COLUMN tax_type varchar(32) NULL;
ALTER TABLE invoice_tax_summary ADD COLUMN tax_type varchar(32) NULL;
