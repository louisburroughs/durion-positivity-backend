-- Add line-item type to invoice items.
-- Carries the workorder item classification (PART, LABOR, FEE, TAX) through to
-- the invoice line item so the billing UI can label each line. Nullable:
-- pre-existing rows and non-workorder creation paths may not carry a type.

SET TIME ZONE 'UTC';

ALTER TABLE invoice_items ADD COLUMN type character varying(32);
