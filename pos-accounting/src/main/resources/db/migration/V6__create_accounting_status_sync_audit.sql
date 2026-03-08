-- Audit table for accounting status synchronization events (AC5: CAP-251 Story #5)
CREATE TABLE accounting_status_sync_audit (
    id              UUID         NOT NULL,
    invoice_id      UUID         NOT NULL,
    old_status      VARCHAR(50),
    new_status      VARCHAR(50)  NOT NULL,
    event_id        VARCHAR(255) NOT NULL,
    synced_at       TIMESTAMP    NOT NULL,
    sync_source     VARCHAR(100) NOT NULL,
    latency_ms      BIGINT,
    CONSTRAINT pk_accounting_status_sync_audit PRIMARY KEY (id)
);

CREATE INDEX idx_sync_audit_invoice_id ON accounting_status_sync_audit (invoice_id);
CREATE INDEX idx_sync_audit_synced_at  ON accounting_status_sync_audit (synced_at);
