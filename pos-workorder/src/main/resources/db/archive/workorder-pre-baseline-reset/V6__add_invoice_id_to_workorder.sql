-- Add invoice_id to workorder for reverse lookup traceability (CAP-007 Story #45)
ALTER TABLE workorder ADD COLUMN IF NOT EXISTS invoice_id UUID;

CREATE INDEX IF NOT EXISTS idx_workorder_invoice_id ON workorder(invoice_id);

COMMENT ON COLUMN workorder.invoice_id IS 'Generated invoice ID linked to this workorder for traceability';
