-- CAP-251 Story #5: Accounting status reconciliation columns for invoice_status_views

ALTER TABLE invoice_status_views
    ADD COLUMN IF NOT EXISTS accounting_status VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS accounting_status_updated_at TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN IF NOT EXISTS posting_reference VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS archived BOOLEAN NOT NULL DEFAULT FALSE;
