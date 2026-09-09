-- CAP-324 (#1230, #1257): MKCAT (C1.2 JSON) marketing catalogue enrichment.
--
-- Staging, not a catalogue. Nothing here is product data: pos-catalog owns what a product IS, and
-- these rows only record what a manufacturer says about how it markets a tread design. The wall
-- matters because a supplier fact that could alter product identity would hand a vendor edit rights
-- over the catalogue (ADR-0044 R1).

CREATE TABLE supplier_mktcat_variant (
    supplier_mktcat_variant_id UUID         PRIMARY KEY,
    vendor_profile_id          UUID         NOT NULL,
    supplier_ref               VARCHAR(100) NOT NULL,

    -- The vendor's own tread design variant id. A design, not an article: one design spans many
    -- sizes, each with its own EAN, so there is no EAN here to match on.
    vendor_variant_id          VARCHAR(100) NOT NULL,

    brand                      VARCHAR(200),
    tread_design               VARCHAR(200),
    tread_design_2             VARCHAR(200),
    product_name               VARCHAR(300),
    vehicle_type               VARCHAR(100),
    seasonality                VARCHAR(100),

    -- Hash of everything published for this variant, texts and image references included.
    -- This is what makes scheduled-diff mode possible at all: without it, "has this changed since
    -- last time" would mean comparing every field of every variant on every run, and a vendor that
    -- republishes its whole catalogue nightly would look like a catalogue that changes nightly.
    content_hash               VARCHAR(64)  NOT NULL,

    -- Raw decoded texts and image references, as published. Kept so a republication can be rebuilt
    -- byte-identically without re-fetching the vendor, which is what makes the two ingestion modes
    -- produce the same event.
    texts_json                 TEXT         NOT NULL,
    images_json                TEXT         NOT NULL,

    -- Whether any artwork is still missing. Not derived from images_json at query time: the retry
    -- runner reads this column, and a scan that parsed JSON per row to find its work would be the
    -- one query guaranteed to get slower as the catalogue grows.
    has_unresolved_images      BOOLEAN      NOT NULL DEFAULT FALSE,

    first_seen_at              timestamp(6) with time zone NOT NULL,
    last_seen_at               timestamp(6) with time zone NOT NULL,
    last_published_at          timestamp(6) with time zone,

    created_at                 timestamp(6) with time zone NOT NULL,
    updated_at                 timestamp(6) with time zone NOT NULL
);

-- One row per variant per vendor. Two vendors may legitimately describe the same tread design, and
-- their marketing copy is not interchangeable.
CREATE UNIQUE INDEX ux_mktcat_variant_profile_variant
    ON supplier_mktcat_variant (vendor_profile_id, vendor_variant_id);

-- The image retry runner's working set.
CREATE INDEX ix_mktcat_variant_unresolved
    ON supplier_mktcat_variant (has_unresolved_images, updated_at);

-- Answering "what did this vendor last send us" without a scan.
CREATE INDEX ix_mktcat_variant_profile_seen
    ON supplier_mktcat_variant (vendor_profile_id, last_seen_at);


-- Change subscriptions the vendor holds on our behalf. Recorded because a subscription we believe
-- exists and the vendor has forgotten is silent: no callbacks arrive, nothing errors, and the
-- catalogue simply stops changing.
CREATE TABLE supplier_mktcat_subscription (
    supplier_mktcat_subscription_id UUID         PRIMARY KEY,
    vendor_profile_id               UUID         NOT NULL,
    supplier_ref                    VARCHAR(100) NOT NULL,
    event_name                      VARCHAR(100) NOT NULL,
    callback_url                    VARCHAR(1000) NOT NULL,

    -- ACTIVE, or FAILED when the vendor refused or could not be reached.
    status                          VARCHAR(20)  NOT NULL,
    failure_detail                  VARCHAR(1000),

    subscribed_at                   timestamp(6) with time zone,
    last_callback_at                timestamp(6) with time zone,

    created_at                      timestamp(6) with time zone NOT NULL,
    updated_at                      timestamp(6) with time zone NOT NULL,

    CONSTRAINT ck_mktcat_sub_status CHECK (status IN ('ACTIVE', 'FAILED', 'CANCELLED'))
);

CREATE UNIQUE INDEX ux_mktcat_sub_profile_event
    ON supplier_mktcat_subscription (vendor_profile_id, event_name);
