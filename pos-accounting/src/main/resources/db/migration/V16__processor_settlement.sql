-- Story F1c (Issue #963): processor settlement reconciliation.
-- Consumes the normalized, processor-agnostic SettlementReportedV1 contract
-- (pos-domain-events, F1a #959) into settlement + line tables, matches lines to
-- ReceivablePayment/APPayment, and posts one batched journal entry per
-- settlement (decisions D-9…D-14). Also creates the read-only per-provider
-- matching-config replica fed by SettlementProviderConfigV1 (ADR-0044 §3).

-- Read-only replica of the payment service's per-provider settlement matching
-- config (compacted topic payment.settlement-config.v1, keyed by provider_code,
-- last-writer-wins). Owner = payment service; accounting never writes it except
-- via the config listener (decision D-9). event_id is the last applied revision.
CREATE TABLE ext_payment_settlement_config (
    provider_code character varying(64) NOT NULL,
    event_id uuid NOT NULL,
    provider_name character varying(128),
    match_reference_field character varying(32) NOT NULL,
    amount_tolerance numeric(19, 4) NOT NULL DEFAULT 0,
    fee_representation character varying(32) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_payment_settlement_config_pkey PRIMARY KEY (provider_code)
);

-- One row per provider settlement (payout). settlement_id is the provider's own
-- identifier and the aggregate id; event_id is the SettlementReportedV1 idempotency
-- key. Header invariant gross = fee + net is enforced by the contract record.
CREATE TABLE processor_settlement (
    settlement_id character varying(128) NOT NULL,
    event_id uuid NOT NULL,
    provider_code character varying(64) NOT NULL,
    settlement_date date NOT NULL,
    currency character varying(3) NOT NULL,
    gross_amount numeric(19, 4) NOT NULL,
    fee_amount numeric(19, 4) NOT NULL,
    net_amount numeric(19, 4) NOT NULL,
    status character varying(20) NOT NULL,
    matched_gross numeric(19, 4) NOT NULL DEFAULT 0,
    line_count integer NOT NULL DEFAULT 0,
    unmatched_count integer NOT NULL DEFAULT 0,
    journal_entry_id uuid,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT processor_settlement_pkey PRIMARY KEY (settlement_id),
    CONSTRAINT processor_settlement_event_uk UNIQUE (event_id),
    CONSTRAINT processor_settlement_status_ck
        CHECK (status IN ('RECEIVED', 'POSTED'))
);

CREATE INDEX idx_processor_settlement_provider ON processor_settlement (provider_code);
CREATE INDEX idx_processor_settlement_status ON processor_settlement (status);

-- One row per settlement line. Matching keys: provider_line_ref / payment_reference
-- (decision D-11), matched on line gross_amount (decision D-12). Lines the consumer
-- cannot map are left UNMATCHED for the review workflow (decision D-10/D-14).
CREATE TABLE processor_settlement_line (
    line_id uuid NOT NULL,
    settlement_id character varying(128) NOT NULL,
    provider_line_ref character varying(255),
    line_type character varying(32) NOT NULL,
    gross_amount numeric(19, 4) NOT NULL,
    fee_amount numeric(19, 4),
    net_amount numeric(19, 4),
    payment_reference character varying(255),
    match_status character varying(20) NOT NULL,
    matched_payment_id uuid,
    matched_payment_type character varying(4),
    writeoff_reason character varying(500),
    writeoff_journal_entry_id uuid,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT processor_settlement_line_pkey PRIMARY KEY (line_id),
    CONSTRAINT processor_settlement_line_settlement_fk
        FOREIGN KEY (settlement_id) REFERENCES processor_settlement (settlement_id),
    CONSTRAINT processor_settlement_line_match_status_ck
        CHECK (match_status IN ('MATCHED', 'UNMATCHED', 'WRITTEN_OFF')),
    CONSTRAINT processor_settlement_line_payment_type_ck
        CHECK (matched_payment_type IS NULL OR matched_payment_type IN ('AR', 'AP'))
);

CREATE INDEX idx_settlement_line_settlement ON processor_settlement_line (settlement_id);
CREATE INDEX idx_settlement_line_match_status ON processor_settlement_line (match_status);
CREATE INDEX idx_settlement_line_payment_ref ON processor_settlement_line (payment_reference);
