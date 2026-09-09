-- CAP-322 / #1228: vendor stock-report snapshots (EDIWheel B2.1).
--
-- A snapshot is what the vendor said it had, for a whole market, at one moment. Snapshots are
-- append-only and never merged: a later one does not correct an earlier one, it describes a
-- different moment, and both stay true of theirs. "Latest" is therefore a query over
-- snapshot_as_of, and a failed fetch leaves the previous snapshot standing as the last thing the
-- vendor actually said.
--
-- H2(MODE=PostgreSQL)-compatible, matching V2/V3/V8/V9.
--
-- No foreign key to supplier_profile, for the reasons V3 records: ADR-0050 §6/§7 require the
-- received-document record to outlive the configuration that produced it.
CREATE TABLE supplier_stock_snapshot (
    snapshot_id uuid NOT NULL,

    vendor_profile_id uuid NOT NULL,
    supplier_ref character varying(100) NOT NULL,
    protocol_version character varying(64) NOT NULL,

    status character varying(32) NOT NULL,

    document_id character varying(255),
    issued_on date,
    -- The VENDOR's statement of when the snapshot was taken. Deliberately separate from fetched_at:
    -- a report fetched at noon may describe stock as of 06:00, and staleness is judged against the
    -- vendor's figure, not against when we happened to ask.
    snapshot_as_of timestamp(6) with time zone,

    buyer_account_number character varying(64) NOT NULL,
    country_code character varying(8),

    fetched_at timestamp(6) with time zone NOT NULL,
    completed_at timestamp(6) with time zone,

    lines_reported integer DEFAULT 0 NOT NULL,
    lines_rejected integer DEFAULT 0 NOT NULL,
    chunk_count integer DEFAULT 0 NOT NULL,
    chunk_size integer DEFAULT 0 NOT NULL,

    exchange_audit_id uuid,
    correlation_id character varying(100) NOT NULL,
    failure_detail character varying(2000),

    created_at timestamp(6) with time zone NOT NULL,
    created_by character varying(100),

    CONSTRAINT pk_supplier_stock_snapshot PRIMARY KEY (snapshot_id),
    -- REJECTED is deliberately distinct from EMPTY: "the vendor reported nothing" and "the vendor
    -- reported something we could not read" are different problems with different owners, and an
    -- operator filtering on EMPTY would never see the second.
    CONSTRAINT ck_sstock_snapshot_status
        CHECK (status IN ('COMPLETED', 'EMPTY', 'REJECTED', 'FAILED'))
);

CREATE INDEX idx_sstock_snapshot_profile_asof ON supplier_stock_snapshot (vendor_profile_id, snapshot_as_of);
CREATE INDEX idx_sstock_snapshot_status ON supplier_stock_snapshot (status);
CREATE INDEX idx_sstock_snapshot_correlation ON supplier_stock_snapshot (correlation_id);

-- ── Reported lines ──────────────────────────────────────────────────────────────────────────
--
-- available_quantity is NULLABLE WITH NO DEFAULT, and that is load-bearing. Null means the vendor
-- reported the article without stating a quantity; 0 means it explicitly reported none. A NOT NULL
-- DEFAULT 0 would silently turn every vendor silence into an out-of-stock, which is the single
-- mistake this feed most needs to avoid.
CREATE TABLE supplier_stock_snapshot_line (
    line_id uuid NOT NULL,

    snapshot_id uuid NOT NULL,
    vendor_profile_id uuid NOT NULL,

    vendor_line_id character varying(64),
    article_ean character varying(64),
    -- Display alias only. Never an identifier (ADR-0053 §5's rule holds for every feed).
    supplier_article_code character varying(64),
    buyers_article_id character varying(64),
    description character varying(512),

    available_quantity integer,

    created_at timestamp(6) with time zone NOT NULL,

    CONSTRAINT pk_supplier_stock_snapshot_line PRIMARY KEY (line_id),
    CONSTRAINT fk_sstock_line_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES supplier_stock_snapshot (snapshot_id)
);

CREATE INDEX idx_sstock_line_snapshot ON supplier_stock_snapshot_line (snapshot_id);
CREATE INDEX idx_sstock_line_ean ON supplier_stock_snapshot_line (vendor_profile_id, article_ean);
