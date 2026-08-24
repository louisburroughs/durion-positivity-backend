-- ADR-0044 §6 (#1306): read-only catalog replica fed by catalog.product.updated and
-- catalog.service.updated on catalog.events.v1. Exists so a campaign's catalogFocusRef can be
-- resolved before the campaign goes out, without a synchronous read into pos-catalog — ADR-0044 R1
-- permits no such edge, and DomainWallsTest fails the build on one.
--
-- Products and services share one table because a catalogFocusRef is resolved by kind and the four
-- kinds it may name (product / sku / service / category) are answered from these same rows: sku and
-- category are product attributes, not aggregates with facts of their own. A category is therefore
-- only known here through the products that carry it, which is the whole of what pos-catalog
-- publishes about it.
--
-- H2(MODE=PostgreSQL)-compatible like the earlier migrations (no Postgres-only syntax).
CREATE TABLE ext_catalog (
    catalog_item_id uuid NOT NULL,
    item_kind character varying(16) NOT NULL,
    name character varying(512),
    sku character varying(128),
    category_id uuid,
    category character varying(255),
    active boolean NOT NULL DEFAULT TRUE,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_catalog_pkey PRIMARY KEY (catalog_item_id),
    CONSTRAINT ext_catalog_item_kind_chk CHECK (item_kind IN ('PRODUCT', 'SERVICE'))
);

-- A reference is written by hand as either an id or a name, so both are lookup paths.
CREATE INDEX idx_ext_catalog_kind_name ON ext_catalog (item_kind, name);
CREATE INDEX idx_ext_catalog_sku ON ext_catalog (sku);
CREATE INDEX idx_ext_catalog_category ON ext_catalog (category_id, category);
