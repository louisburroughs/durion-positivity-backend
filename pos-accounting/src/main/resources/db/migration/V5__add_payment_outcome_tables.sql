-- CAP-251 Story #6: Payment outcome tables and invoice_status_views columns

CREATE TABLE IF NOT EXISTS reconciliation_records (
    id UUID NOT NULL,
    invoice_id UUID NOT NULL,
    transaction_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_reconciliation_records PRIMARY KEY (id),
    CONSTRAINT uq_reconciliation_invoice_transaction UNIQUE (invoice_id, transaction_id)
);

ALTER TABLE invoice_status_views
    ADD COLUMN IF NOT EXISTS total_amount_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS outstanding_amount_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS paid_amount_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS posting_error BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS latest_idempotency_key VARCHAR(255) NULL;
