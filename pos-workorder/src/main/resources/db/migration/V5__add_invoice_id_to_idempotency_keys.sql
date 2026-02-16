-- Add invoice_id to idempotency_keys for invoice generation idempotency
ALTER TABLE idempotency_keys ADD COLUMN invoice_id UUID;

COMMENT ON COLUMN idempotency_keys.invoice_id IS 'ID of the invoice generated with this key (nullable)';
