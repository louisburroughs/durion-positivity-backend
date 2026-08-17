-- CAP-324 #1352: MKCAT marketing enrichment attaches to a design, not to a product. One tread
-- design spans many product sizes; product.tread_design_id is the only place that association is
-- recorded, and it is nullable and does nothing else — the columns that make a product what it is
-- (dimensions, load index, article code, price) are untouched here and always will be.

CREATE TABLE tread_design (
    id uuid NOT NULL,

    vendor_profile_id uuid NOT NULL,
    supplier_ref character varying(100) NOT NULL,
    vendor_variant_id character varying(100) NOT NULL,

    brand character varying(200),
    tread_design character varying(200),
    tread_design_2 character varying(200),
    product_name character varying(300),
    vehicle_type character varying(100),
    seasonality character varying(100),

    content_hash character varying(64) NOT NULL,
    has_unresolved_images boolean NOT NULL,

    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,

    CONSTRAINT pk_tread_design PRIMARY KEY (id),
    CONSTRAINT uk_tread_design_vendor_variant UNIQUE (vendor_profile_id, vendor_variant_id)
);

CREATE INDEX idx_tread_design_brand ON tread_design (brand);

-- ── Localized marketing copy, replaced wholesale per design on every apply ────────────────────
CREATE TABLE tread_design_text (
    id uuid NOT NULL,
    tread_design_id uuid NOT NULL,
    language_code character varying(16) NOT NULL,

    name character varying(300),
    description text,
    foot_notes text,

    CONSTRAINT pk_tread_design_text PRIMARY KEY (id),
    CONSTRAINT uk_tread_design_text_design_language UNIQUE (tread_design_id, language_code),
    CONSTRAINT fk_tread_design_text_design
        FOREIGN KEY (tread_design_id) REFERENCES tread_design (id) ON DELETE CASCADE
);

-- ── Artwork references, replaced wholesale per design on every apply ───────────────────────────
CREATE TABLE tread_design_image (
    id uuid NOT NULL,
    tread_design_id uuid NOT NULL,

    image_type character varying(100),
    -- pos-image identifier; absent while unresolved.
    image_id bigint,
    content_hash character varying(64),
    source_uri character varying(1000),
    unresolved boolean NOT NULL,

    CONSTRAINT pk_tread_design_image PRIMARY KEY (id),
    CONSTRAINT fk_tread_design_image_design
        FOREIGN KEY (tread_design_id) REFERENCES tread_design (id) ON DELETE CASCADE
);

-- ── The association: nullable, additive, and the only column this story touches on product ─────
-- ON DELETE SET NULL rather than CASCADE or RESTRICT: a design row being cleaned up or re-keyed
-- must not delete a product, nor block the cleanup — it should simply leave the product
-- unmatched again, the same state it was in before a match was ever found.
ALTER TABLE product ADD COLUMN tread_design_id uuid;
ALTER TABLE product ADD CONSTRAINT fk_product_tread_design
    FOREIGN KEY (tread_design_id) REFERENCES tread_design (id) ON DELETE SET NULL;
CREATE INDEX idx_product_tread_design ON product (tread_design_id);
