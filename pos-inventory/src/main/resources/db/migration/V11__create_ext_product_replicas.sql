-- odoo-parity B1 (#1033, spec §3): catalog product replica fed by catalog.product.updated v2
-- facts on catalog.events.v1 (X1, #1023). Carries the per-product base UoM + conversion set for
-- the UoM conversion service (B1/B3), the tracking level (consumed by E1), and substitution-group
-- membership (consumed by G2) — one listener maintains all v2 fields so later stories add no
-- further catalog consumers.

-- Parent replica row: one per product, stale-guarded on the envelope aggregateVersion
-- (catalog stamps product updatedAt epoch millis there; equal versions must apply because
-- catalog fans out multiple facts in the same millisecond on group-membership changes).
CREATE TABLE ext_product (
    product_id uuid NOT NULL,
    base_uom character varying(32),
    tracking_level character varying(16) DEFAULT 'NONE' NOT NULL,
    substitution_group_id uuid,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_product_pkey PRIMARY KEY (product_id)
);

-- Per-product UoM conversion set; every fact carries the full set — replaced wholesale
-- (ext_workorder_part pattern). Mirrors catalog's product_uom column types (V7 there).
CREATE TABLE ext_product_uom (
    product_id uuid NOT NULL,
    uom_code character varying(32) NOT NULL,
    uom_type character varying(16),
    factor_to_base numeric(20, 6) NOT NULL,
    precision_scale integer NOT NULL,
    CONSTRAINT ext_product_uom_pkey PRIMARY KEY (product_id, uom_code)
);

-- Substitution-group members of the product's group (including the product itself), replaced
-- wholesale per fact. Child table rather than an array column — no replica stores lists as
-- arrays/jsonb in this module.
CREATE TABLE ext_product_substitution (
    product_id uuid NOT NULL,
    member_product_id uuid NOT NULL,
    CONSTRAINT ext_product_substitution_pkey PRIMARY KEY (product_id, member_product_id)
);
CREATE INDEX idx_ext_product_substitution_member ON ext_product_substitution (member_product_id);
