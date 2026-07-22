-- Issue #998 (parity-T8 Phase-2, scope item 2): period-close-aligned, auditable freeze of the
-- Sales-Tax Liability report. A snapshot captures the T8 report (issue #966 / PR #995) exactly as
-- generated for a CLOSED accounting period, together with a canonical SHA-256 content hash so the
-- frozen figures can later be re-derived from the live replicas and compared (re-derivability
-- audit). Snapshot content is immutable after insert; the only permitted lifecycle change is
-- ACTIVE -> SUPERSEDED when a period is reopened, adjusted, re-closed, and re-frozen. Provider
-- neutral by design (see the #998 decision record): no filing-provider identifiers anywhere.
--
-- FLYWAY COLLISION NOTE: numbered V20 as the next contiguous version after V19. Unmerged
-- accounting-plan branches may also claim a V2x on pos-accounting; if they merge first this file
-- must be renumbered on rebase.

CREATE TABLE tax_liability_snapshot (
    snapshot_id uuid NOT NULL,
    period_id uuid NOT NULL,
    period_code character varying(7) NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    status character varying(10) NOT NULL,
    period_closed_at timestamp(6) with time zone NOT NULL,
    content_hash character varying(64) NOT NULL,
    total_taxable_base numeric(19, 4) NOT NULL,
    total_exempt_base numeric(19, 4) NOT NULL,
    total_tax_collected_gross numeric(19, 4) NOT NULL,
    total_credits_netted numeric(19, 4) NOT NULL,
    total_net_tax numeric(19, 4) NOT NULL,
    tax_payable_account_code character varying(20) NOT NULL,
    gl_net_activity numeric(19, 4) NOT NULL,
    gl_drift numeric(19, 4) NOT NULL,
    reconciled boolean NOT NULL,
    supersedes_snapshot_id uuid,
    superseded_at timestamp(6) with time zone,
    superseded_by character varying(50),
    frozen_at timestamp(6) with time zone NOT NULL,
    frozen_by character varying(50) NOT NULL,
    CONSTRAINT tax_liability_snapshot_pkey PRIMARY KEY (snapshot_id),
    CONSTRAINT fk_tax_liability_snapshot_period
        FOREIGN KEY (period_id) REFERENCES accounting_period (period_id),
    CONSTRAINT tax_liability_snapshot_status_check
        CHECK ((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUPERSEDED'::character varying])::text[])),
    CONSTRAINT tax_liability_snapshot_date_check CHECK (start_date <= end_date)
);

-- At most one ACTIVE snapshot per period; superseded history is unbounded.
CREATE UNIQUE INDEX uq_tax_liability_snapshot_active
    ON tax_liability_snapshot (period_id)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_tax_liability_snapshot_period_code ON tax_liability_snapshot (period_code);

CREATE TABLE tax_liability_snapshot_row (
    row_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    row_order integer NOT NULL,
    jurisdiction_type character varying(32) NOT NULL,
    jurisdiction_code character varying(64) NOT NULL,
    jurisdiction_name character varying(255),
    taxable_base numeric(19, 4) NOT NULL,
    exempt_base numeric(19, 4) NOT NULL,
    exemption_reasons character varying(1000) NOT NULL DEFAULT '',
    tax_collected_gross numeric(19, 4) NOT NULL,
    credits_netted numeric(19, 4) NOT NULL,
    net_tax numeric(19, 4) NOT NULL,
    CONSTRAINT tax_liability_snapshot_row_pkey PRIMARY KEY (row_id),
    CONSTRAINT fk_tax_liability_snapshot_row_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES tax_liability_snapshot (snapshot_id) ON DELETE CASCADE,
    CONSTRAINT uq_tax_liability_snapshot_row_order UNIQUE (snapshot_id, row_order)
);

CREATE INDEX idx_tax_liability_snapshot_row_snapshot ON tax_liability_snapshot_row (snapshot_id, row_order);
