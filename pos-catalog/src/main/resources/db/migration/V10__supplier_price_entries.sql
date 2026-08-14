-- CAP-318 / #1308: supplier price entries replace supplier_item_cost (ADR-0053 §1–§2).
--
-- supplier_item_cost held ONE MUTABLE ROW per (supplier_id, item_id): no effective dates, no source
-- document, no market scope, and no history beyond updated_at. A vendor price change destroyed the
-- previous one, and nothing could answer "where did this cost come from" or "what did we pay in
-- March". Both tables are dropped here — pre-production, no compatibility shim per platform policy.

DROP TABLE IF EXISTS cost_tier;
DROP TABLE IF EXISTS supplier_item_cost;

-- ── Supplier price entries: append-only, effective-dated, source-identified ──────────────────
--
-- Rows are immutable. A new vendor price APPENDS; nothing updates in place. Effective windows are
-- half-open [effective_from, next effective_from) per (vendor_profile_id, product_id, country_code,
-- currency), and superseded rows are retained because cost history is a first-class query.
--
-- No foreign key to product: the match was made upstream in pos-supplier against a replica of
-- catalog product codes, and a product deleted afterwards must not take the commercial record of
-- what a vendor quoted with it. product_id is the join key and is expected to resolve.
CREATE TABLE supplier_price_entry (
    id uuid NOT NULL,

    vendor_profile_id uuid NOT NULL,
    supplier_ref character varying(100) NOT NULL,

    product_id uuid NOT NULL,
    match_method character varying(32) NOT NULL,
    article_ean character varying(64),
    -- Display alias only. Never an identifier, never a match key (ADR-0053 §5).
    supplier_article_code character varying(64),
    x_reference_code character varying(64),

    -- Market scope (ADR-0053 §3): buyer account and market. No location column, deliberately.
    buyer_account_number character varying(64) NOT NULL,
    country_code character varying(8) NOT NULL,
    currency character varying(8) NOT NULL,

    -- NULL means the vendor did not state the value. It does not mean zero.
    suggested_retail_price numeric(19, 4),
    gross_price numeric(19, 4),
    net_price numeric(19, 4),
    tax_rate numeric(9, 4),
    recycling_fee numeric(19, 4),

    effective_from date NOT NULL,

    source_document_id character varying(255),
    source_document_date date,
    import_manifest_id uuid NOT NULL,
    fetched_at timestamp(6) with time zone NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,

    CONSTRAINT pk_supplier_price_entry PRIMARY KEY (id),
    CONSTRAINT ck_supplier_price_entry_match_method
        CHECK (match_method IN ('EAN', 'UPC_XREFERENCE'))
);

CREATE INDEX idx_supplier_price_entry_latest
    ON supplier_price_entry (product_id, vendor_profile_id, country_code, currency, effective_from);
CREATE INDEX idx_supplier_price_entry_import ON supplier_price_entry (import_manifest_id);
CREATE INDEX idx_supplier_price_entry_product ON supplier_price_entry (product_id);

-- ── Import bookkeeping: how much of an import this module has applied (ADR-0053 §7) ──────────
--
-- Chunks and the completion event arrive independently and at-least-once, so "did I get all of it"
-- cannot be answered from the price rows: a missing chunk looks exactly like a vendor that sent
-- fewer lines. This table is the answer, and an INCOMPLETE row is what triggers the re-emit request
-- on the owner's command topic (ADR-0044 §4).
CREATE TABLE supplier_price_import (
    import_manifest_id uuid NOT NULL,
    vendor_profile_id uuid NOT NULL,
    supplier_ref character varying(100),

    chunks_applied integer DEFAULT 0 NOT NULL,
    lines_applied integer DEFAULT 0 NOT NULL,
    expected_chunk_count integer,
    expected_line_count integer,
    content_checksum character varying(64),

    status character varying(32) NOT NULL,
    completed_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,

    CONSTRAINT pk_supplier_price_import PRIMARY KEY (import_manifest_id),
    CONSTRAINT ck_supplier_price_import_status
        CHECK (status IN ('APPLYING', 'COMPLETE', 'INCOMPLETE'))
);

CREATE INDEX idx_supplier_price_import_profile ON supplier_price_import (vendor_profile_id);
CREATE INDEX idx_supplier_price_import_status ON supplier_price_import (status);
