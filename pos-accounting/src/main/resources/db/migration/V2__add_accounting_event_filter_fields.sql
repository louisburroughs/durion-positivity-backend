ALTER TABLE accounting_event
    ADD COLUMN IF NOT EXISTS idempotency_outcome VARCHAR(20),
    ADD COLUMN IF NOT EXISTS ingestion_id UUID,
    ADD COLUMN IF NOT EXISTS domain_key_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS invoice_id UUID;

CREATE INDEX IF NOT EXISTS idx_accounting_event_ingestion_id
    ON accounting_event (ingestion_id);

CREATE INDEX IF NOT EXISTS idx_accounting_event_domain_key_id
    ON accounting_event (domain_key_id);

CREATE INDEX IF NOT EXISTS idx_accounting_event_invoice_id
    ON accounting_event (invoice_id);
