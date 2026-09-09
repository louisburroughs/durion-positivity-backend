-- CAP-318 / #1224: PRICAT (B4.0) import staging, unmatched-line quarantine, and the supplier
-- event outbox (ADR-0053 §1/§2/§5/§7, ADR-0044 §4).
--
-- DDL is kept H2(MODE=PostgreSQL)-compatible, matching V2/V3, so the dev profile and JPA slices
-- can run these migrations.
--
-- DELIBERATELY NO FOREIGN KEY to supplier_profile, for the same two reasons V3 records for the
-- exchange audit: ADR-0050 §6/§7 require the received-document record to outlive the configuration
-- that produced it, and admin deleteProfile is a hard delete. vendor_profile_id is the identity for
-- every query; supplier_ref is a snapshot for readability only.

-- ── Per-binding chunk size (ADR-0053 §7) ────────────────────────────────────────────────────
--
-- NULL means "use the deployment default". The right chunk size is a property of one vendor's
-- documents, so a deployment talking to two vendors is not forced to pick one number for both.
ALTER TABLE supplier_endpoint_binding ADD COLUMN pricat_chunk_size integer;

-- ── Import manifest: one row per fetch, the event aggregate (ADR-0053 §7) ───────────────────
CREATE TABLE supplier_pricat_import (
    import_manifest_id uuid NOT NULL,

    vendor_profile_id uuid NOT NULL,
    supplier_ref character varying(100) NOT NULL,
    protocol_family character varying(64) NOT NULL,
    protocol_version character varying(64) NOT NULL,

    status character varying(32) NOT NULL,

    source_document_id character varying(255),
    source_document_date date,

    -- Market scope (ADR-0053 §3). No location column exists on purpose: PRICAT is scoped by buyer
    -- account and market, and inventing a per-location scope would fabricate a fact the vendor
    -- never stated.
    buyer_account_number character varying(64) NOT NULL,
    buyer_agency_code character varying(16),
    country_code character varying(8),
    currency character varying(8),

    fetched_at timestamp(6) with time zone NOT NULL,
    completed_at timestamp(6) with time zone,

    -- Completeness counters: matched + unmatched + duplicate = fetched for every terminal import.
    lines_fetched integer DEFAULT 0 NOT NULL,
    lines_matched integer DEFAULT 0 NOT NULL,
    lines_unmatched integer DEFAULT 0 NOT NULL,
    lines_duplicate integer DEFAULT 0 NOT NULL,

    chunk_count integer DEFAULT 0 NOT NULL,
    chunk_size integer DEFAULT 0 NOT NULL,
    content_checksum character varying(64),

    exchange_audit_id uuid,
    correlation_id character varying(100) NOT NULL,
    failure_detail character varying(2000),

    created_at timestamp(6) with time zone NOT NULL,
    created_by character varying(100),

    CONSTRAINT pk_supplier_pricat_import PRIMARY KEY (import_manifest_id),
    CONSTRAINT ck_spricat_import_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'EMPTY', 'FAILED')),
    CONSTRAINT ck_spricat_import_counts
        CHECK (lines_fetched >= 0 AND lines_matched >= 0 AND lines_unmatched >= 0 AND lines_duplicate >= 0)
);

CREATE INDEX idx_spricat_import_profile_fetched ON supplier_pricat_import (vendor_profile_id, fetched_at);
CREATE INDEX idx_spricat_import_status ON supplier_pricat_import (status);
CREATE INDEX idx_spricat_import_correlation ON supplier_pricat_import (correlation_id);

-- ── Staged lines: append-only received-document records (ADR-0053 §1/§2) ────────────────────
--
-- Immutable and never superseded in place. "Latest" is a query over effective_from, so cost
-- history stays queryable; an UPDATE here would be the overwrite-on-save defect ADR-0053 calls out
-- in pos-price.
CREATE TABLE supplier_pricat_entry (
    entry_id uuid NOT NULL,

    import_manifest_id uuid NOT NULL,
    vendor_profile_id uuid NOT NULL,
    position_number integer,

    article_ean character varying(64),
    -- Alias for display only. Never an identifier, never a match key (ADR-0053 §5).
    supplier_article_code character varying(64),
    x_reference_code character varying(64),

    matched_product_id uuid,
    match_method character varying(32) NOT NULL,

    suggested_retail_price numeric(19, 4),
    gross_price numeric(19, 4),
    net_price numeric(19, 4),
    tax_rate numeric(9, 4),
    recycling_fee numeric(19, 4),

    -- Inclusive start of a half-open window closed by the next entry (ADR-0053 §2). A vendor-local
    -- date, deliberately not an instant.
    effective_from date NOT NULL,

    buyer_account_number character varying(64) NOT NULL,
    country_code character varying(8) NOT NULL,
    currency character varying(8) NOT NULL,

    source_document_id character varying(255),
    source_document_date date,
    fetched_at timestamp(6) with time zone NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,

    CONSTRAINT pk_supplier_pricat_entry PRIMARY KEY (entry_id),
    CONSTRAINT fk_spricat_entry_import
        FOREIGN KEY (import_manifest_id) REFERENCES supplier_pricat_import (import_manifest_id),
    CONSTRAINT ck_spricat_entry_match_method
        CHECK (match_method IN ('EAN', 'UPC_XREFERENCE', 'UNMATCHED'))
);

CREATE INDEX idx_spricat_entry_import ON supplier_pricat_entry (import_manifest_id);
CREATE INDEX idx_spricat_entry_latest
    ON supplier_pricat_entry (vendor_profile_id, matched_product_id, country_code, currency, effective_from);
CREATE INDEX idx_spricat_entry_ean ON supplier_pricat_entry (vendor_profile_id, article_ean);

-- ── Unmatched-line quarantine: zero silent drops (ADR-0053 §5) ──────────────────────────────
--
-- Values are kept alongside identifiers so a line can be re-applied from here after a catalog fix,
-- without asking the vendor for the document again.
CREATE TABLE supplier_pricat_unmatched_line (
    unmatched_line_id uuid NOT NULL,

    import_manifest_id uuid NOT NULL,
    vendor_profile_id uuid NOT NULL,
    position_number integer,

    article_ean character varying(64),
    supplier_article_code character varying(64),
    x_reference_code character varying(64),

    reason character varying(48) NOT NULL,
    reason_detail character varying(1000),

    suggested_retail_price numeric(19, 4),
    gross_price numeric(19, 4),
    net_price numeric(19, 4),
    tax_rate numeric(9, 4),
    recycling_fee numeric(19, 4),

    effective_from date,
    buyer_account_number character varying(64) NOT NULL,
    country_code character varying(8),
    currency character varying(8),

    source_document_id character varying(255),
    source_document_date date,
    fetched_at timestamp(6) with time zone NOT NULL,

    resolved_at timestamp(6) with time zone,
    resolved_product_id uuid,

    created_at timestamp(6) with time zone NOT NULL,

    CONSTRAINT pk_supplier_pricat_unmatched_line PRIMARY KEY (unmatched_line_id),
    CONSTRAINT fk_spricat_unmatched_import
        FOREIGN KEY (import_manifest_id) REFERENCES supplier_pricat_import (import_manifest_id),
    CONSTRAINT ck_spricat_unmatched_reason
        CHECK (reason IN ('NO_IDENTIFIER', 'NO_CATALOG_MATCH', 'AMBIGUOUS_CATALOG_MATCH',
                          'CATALOG_UNAVAILABLE', 'DUPLICATE_LINE', 'MALFORMED_LINE'))
);

CREATE INDEX idx_spricat_unmatched_import ON supplier_pricat_unmatched_line (import_manifest_id);
CREATE INDEX idx_spricat_unmatched_open ON supplier_pricat_unmatched_line (vendor_profile_id, resolved_at);
CREATE INDEX idx_spricat_unmatched_reason ON supplier_pricat_unmatched_line (reason);

-- ── Transactional outbox (ADR-0044 §4) ──────────────────────────────────────────────────────
CREATE TABLE supplier_event_outbox (
    id uuid NOT NULL,
    topic character varying(255) NOT NULL,
    record_key character varying(255) NOT NULL,
    event_type character varying(128) NOT NULL,
    payload text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    published_at timestamp(6) with time zone,
    attempts integer DEFAULT 0 NOT NULL,
    last_error text,

    CONSTRAINT pk_supplier_event_outbox PRIMARY KEY (id)
);

CREATE INDEX idx_soutbox_unpublished ON supplier_event_outbox (published_at, id);
