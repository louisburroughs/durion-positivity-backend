-- CAP-322 (#1312): supplier stock-report snapshots consumed as availability hints.
--
-- These tables hold what a VENDOR said about ITS OWN stock. They are not owned stock, they never
-- enter valuation (ADR-0048) and they are never read by an on-hand ATP path. That guarantee is
-- structural rather than procedural: the hints live in their own tables that no valuation and no
-- ATP query joins, and an ArchUnit rule asserts that only the supplier-hint classes may reach the
-- repository. The alternative considered and rejected was an attribute on distributor_normalized_
-- inventory / normalized_availability, both of which declare product_id and their quantity column
-- NOT NULL and so cannot represent either an unresolved article or a vendor-listed article with no
-- stated quantity -- the two cases this feed exists to preserve.

-- One row per (vendor, article identity): the latest thing that vendor said about that article.
--
-- Latest-only, not append-only. This is a cache of a vendor's last statement, not a ledger; the
-- producer retains full snapshot history in pos-supplier's stock_snapshot_line for audit.
CREATE TABLE supplier_stock_hint (
    hint_id               uuid NOT NULL,
    vendor_profile_id     uuid NOT NULL,

    -- The key. A stock-report line carries up to three identifiers and the producer guarantees
    -- only that at least ONE is non-null, so the identifiers alone are not a key. One is elected
    -- as the row's identity following the producer's own precedence (SupplierStockReportLine
    -- .describeIdentity): EAN, else the buyer's article code, else the vendor's article code.
    -- identity_kind makes "the vendor identified this by EAN" a queryable fact rather than an
    -- inference drawn from which columns happen to be null.
    identity_kind         character varying(20) NOT NULL,
    identity_value        character varying(255) NOT NULL,

    -- Every identifier the vendor stated, verbatim, whichever one was elected as the identity.
    article_ean           character varying(64),
    supplier_article_code character varying(255),
    buyers_article_id     character varying(255),
    description           character varying(512),

    -- The three-way distinction the producer preserves, preserved here:
    --   * a value >= 0  -- the vendor stated this many; 0 means the vendor explicitly said none
    --   * NULL          -- the vendor listed the article and stated no quantity at all
    --   * no row        -- the vendor did not mention the article
    -- Nullable with no default on purpose: a default would collapse "stated nothing" into "none",
    -- and a vendor's silence must never read as an out-of-stock.
    available_quantity    integer,

    -- Article resolution runs OUT of the consumer, against pos-catalog's deterministic exact-match
    -- EAN/UPC lookup (ADR-0053 SS5) -- the same matching foundation the PRICAT consumer uses. An
    -- unresolved row is retained and visible, never dropped: pos-inventory holds no product-code
    -- data of its own, so an article we cannot resolve today may resolve once catalog carries it.
    resolution_status     character varying(20) NOT NULL,
    resolved_product_id   uuid,
    resolved_at           timestamp(6) with time zone,
    resolved_by           character varying(64),

    -- Lineage back to the snapshot that last wrote this row, so a caller can ask whether the
    -- snapshot it came from ever arrived complete.
    source_snapshot_id      uuid NOT NULL,
    source_chunk_sequence   integer NOT NULL,
    supplier_ref            character varying(255),

    -- Both timestamps are retained, as the story requires. snapshot_as_of is the VENDOR's stated
    -- instant and is nullable -- B2.1 permits a vendor to state no time -- so as_of_source records
    -- whether the age shown to a caller is the vendor's figure or merely our fetch clock. Freshness
    -- is judged on the vendor's figure when there is one; supersession is ordered on fetched_at,
    -- which is the one instant that always exists and that we always trust.
    snapshot_as_of        timestamp(6) with time zone,
    as_of_source          character varying(10) NOT NULL,
    fetched_at            timestamp(6) with time zone NOT NULL,

    first_seen_at         timestamp(6) with time zone NOT NULL,
    created_at            timestamp(6) with time zone NOT NULL,
    updated_at            timestamp(6) with time zone NOT NULL,
    CONSTRAINT supplier_stock_hint_pkey PRIMARY KEY (hint_id),
    CONSTRAINT ck_supplier_stock_hint_identity_kind
        CHECK (identity_kind IN ('EAN', 'BUYER_ARTICLE', 'SUPPLIER_ARTICLE')),
    CONSTRAINT ck_supplier_stock_hint_as_of_source
        CHECK (as_of_source IN ('VENDOR', 'FETCH')),
    CONSTRAINT ck_supplier_stock_hint_resolution_status
        CHECK (resolution_status IN ('PENDING', 'RESOLVED', 'UNRESOLVED', 'NOT_RESOLVABLE')),
    -- A negative quantity is a vendor stating nonsense; NULL stays legal and distinct from zero.
    CONSTRAINT ck_supplier_stock_hint_quantity CHECK (available_quantity IS NULL OR available_quantity >= 0)
);

CREATE UNIQUE INDEX ux_supplier_stock_hint_identity
    ON supplier_stock_hint (vendor_profile_id, identity_kind, identity_value);

-- Read path: hints for a product, one row per vendor (never aggregated across vendors -- summing
-- two vendors' figures would invent a number no vendor stated).
CREATE INDEX idx_supplier_stock_hint_product ON supplier_stock_hint (resolved_product_id);
-- Read path: hints for a code the caller holds, whichever identifier was elected as the identity.
CREATE INDEX idx_supplier_stock_hint_ean ON supplier_stock_hint (article_ean);
CREATE INDEX idx_supplier_stock_hint_buyer_code ON supplier_stock_hint (buyers_article_id);
CREATE INDEX idx_supplier_stock_hint_supplier_code ON supplier_stock_hint (supplier_article_code);
-- Resolver sweep: the PENDING backlog.
CREATE INDEX idx_supplier_stock_hint_resolution ON supplier_stock_hint (resolution_status);
CREATE INDEX idx_supplier_stock_hint_snapshot ON supplier_stock_hint (source_snapshot_id);

-- One row per snapshot: how much of it arrived.
--
-- supplier.stockreport.updated has no terminal event and no republish command (unlike PRICAT), and
-- an empty/rejected/failed snapshot publishes no events at all. Completeness is therefore in-band
-- only: count the chunks that arrived and compare against the chunk_count every chunk carries.
-- PARTIAL is the resting state, so a chunk that never arrives needs no timer to be noticed -- the
-- receipt simply never completes and the read model says so.
CREATE TABLE supplier_stock_snapshot_receipt (
    receipt_id            uuid NOT NULL,
    snapshot_id           uuid NOT NULL,
    vendor_profile_id     uuid NOT NULL,
    supplier_ref          character varying(255),
    document_id           character varying(255),
    buyer_account_number  character varying(64),
    chunk_count           integer NOT NULL,
    chunks_applied        integer NOT NULL,
    -- Producer-stated total across ALL chunks, counting decoded lines only (rejected lines are
    -- excluded upstream), so this is a cross-check on completeness and not an authoritative total.
    lines_reported        integer NOT NULL,
    lines_applied         integer NOT NULL,
    snapshot_as_of        timestamp(6) with time zone,
    as_of_source          character varying(10) NOT NULL,
    fetched_at            timestamp(6) with time zone NOT NULL,
    complete              boolean NOT NULL,
    first_chunk_at        timestamp(6) with time zone NOT NULL,
    last_chunk_at         timestamp(6) with time zone NOT NULL,
    created_at            timestamp(6) with time zone NOT NULL,
    updated_at            timestamp(6) with time zone NOT NULL,
    CONSTRAINT supplier_stock_snapshot_receipt_pkey PRIMARY KEY (receipt_id),
    CONSTRAINT ck_supplier_stock_receipt_as_of_source CHECK (as_of_source IN ('VENDOR', 'FETCH'))
);

CREATE UNIQUE INDEX ux_supplier_stock_snapshot_receipt_snapshot
    ON supplier_stock_snapshot_receipt (snapshot_id);
CREATE INDEX idx_supplier_stock_snapshot_receipt_vendor
    ON supplier_stock_snapshot_receipt (vendor_profile_id, fetched_at);

-- Applied-chunk log: which sequences of a snapshot were applied.
--
-- The processed_events guard dedupes a redelivery of the same event; it cannot dedupe a re-emit of
-- the same chunk under a fresh eventId, which is what this table is for (the same second guard the
-- PRICAT consumer keeps in supplier_price_import_chunk). It also makes a gap enumerable rather
-- than merely countable.
CREATE TABLE supplier_stock_snapshot_chunk (
    chunk_id        uuid NOT NULL,
    snapshot_id     uuid NOT NULL,
    chunk_sequence  integer NOT NULL,
    lines_in_chunk  integer NOT NULL,
    applied_at      timestamp(6) with time zone NOT NULL,
    CONSTRAINT supplier_stock_snapshot_chunk_pkey PRIMARY KEY (chunk_id),
    CONSTRAINT ck_supplier_stock_chunk_sequence CHECK (chunk_sequence >= 1)
);

CREATE UNIQUE INDEX ux_supplier_stock_snapshot_chunk_seq
    ON supplier_stock_snapshot_chunk (snapshot_id, chunk_sequence);
