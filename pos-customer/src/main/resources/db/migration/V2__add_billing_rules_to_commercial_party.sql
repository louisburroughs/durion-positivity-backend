-- V2: Add billing rules columns to commercial_party table (B-22 SDK migration)
ALTER TABLE commercial_party
    ADD COLUMN IF NOT EXISTS br_po_required BOOLEAN,
    ADD COLUMN IF NOT EXISTS br_tax_exempt BOOLEAN,
    ADD COLUMN IF NOT EXISTS br_credit_hold BOOLEAN,
    ADD COLUMN IF NOT EXISTS br_auto_pay_enabled BOOLEAN,
    ADD COLUMN IF NOT EXISTS br_payment_terms VARCHAR(100),
    ADD COLUMN IF NOT EXISTS br_credit_limit NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS br_currency VARCHAR(3),
    ADD COLUMN IF NOT EXISTS br_invoice_delivery_method VARCHAR(50),
    ADD COLUMN IF NOT EXISTS br_billing_address_id UUID,
    ADD COLUMN IF NOT EXISTS br_discount_policy_ref VARCHAR(200);
