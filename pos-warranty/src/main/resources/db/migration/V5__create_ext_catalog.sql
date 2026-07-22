-- ADR-0044 §6 (#924): read-only catalog product replica fed by catalog.product.updated facts on
-- catalog.events.v1. Replaces the synchronous CatalogClient.getProduct read used by candidate-line
-- product resolution and warranty eligibility (manufacturer / warranty terms).
-- H2(MODE=PostgreSQL)-compatible like V1 (no Postgres-only syntax).
CREATE TABLE ext_catalog (
    product_id uuid NOT NULL,
    sku character varying(128),
    name character varying(512),
    manufacturer_id uuid,
    manufacturer_name character varying(255),
    manufacturer_brand character varying(255),
    category_id uuid,
    category character varying(255),
    warranty character varying(2048),
    manufacturer_warranty character varying(2048),
    active boolean NOT NULL DEFAULT true,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_catalog_pkey PRIMARY KEY (product_id)
);
