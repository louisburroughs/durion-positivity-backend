-- Story T6 (decision D-T3): provider tax-document lifecycle log. When the tax provider is
-- unavailable at invoice finalization the platform must NOT block the sale — it finalizes
-- on the last estimate and records a PENDING_COMMIT row here; a scheduled re-commit job
-- promotes it to COMMITTED when the provider recovers (estimate-and-true-up). A document is
-- VOIDED when its invoice reverts to DRAFT (InvoiceStatus has no CANCELLED/VOIDED, R-T2).
-- One row per source document: the UNIQUE reference_id gives commit idempotency (a commit
-- retried with the same reference_id updates the single row rather than duplicating it).
-- Written Postgres-style and validated on H2 (PostgreSQL mode) in tests.
CREATE TABLE tax_provider_transaction (
    id                      uuid NOT NULL,
    reference_id            uuid NOT NULL,
    reference_type          character varying(32),
    provider                character varying(64) NOT NULL,
    status                  character varying(16) NOT NULL,
    external_transaction_id character varying(128),
    attempts                integer NOT NULL DEFAULT 0,
    last_error              character varying(1024),
    created_at              timestamp(6) with time zone NOT NULL,
    updated_at              timestamp(6) with time zone NOT NULL,
    CONSTRAINT tax_provider_transaction_pkey PRIMARY KEY (id)
);

-- Idempotency: at most one lifecycle row per source document.
CREATE UNIQUE INDEX ux_tax_provider_transaction_reference
    ON tax_provider_transaction (reference_id);

-- Re-commit job + backlog gauge scan by status.
CREATE INDEX idx_tax_provider_transaction_status
    ON tax_provider_transaction (status);
