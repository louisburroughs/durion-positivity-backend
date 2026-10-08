-- CAP:550 S32a (louisburroughs/durion-positivity-backend#2636): each persisted tax row carries the
-- tax-type code pos-tax priced it with (pos-tax-common TaxType, e.g. GST), copied as received and
-- never inferred. NULL for an untyped row: every existing row is US and stays NULL permanently. No
-- default, no CHECK (pos-tax-common owns the value set) and no backfill: rows freeze at finalization
-- (BILL-DEC-004), and a DRAFT invoice picks the type up on its next re-price. Tenant-scoped; RLS unchanged.
ALTER TABLE invoice_line_tax    ADD COLUMN tax_type varchar(32) NULL;
ALTER TABLE invoice_tax_summary ADD COLUMN tax_type varchar(32) NULL;
