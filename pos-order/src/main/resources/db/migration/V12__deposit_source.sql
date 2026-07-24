-- Wave 5 (odoo parity #1085): deposit / down-payment take.
-- A deposit-take order holds a deposit against an estimate/workorder; at checkout pos-invoice
-- registers a dedicated deposit credit (gate V2 artifact) keyed by this source reference.

ALTER TABLE sales_order ADD COLUMN deposit_source_type VARCHAR(16);
ALTER TABLE sales_order ADD COLUMN deposit_source_id UUID;
