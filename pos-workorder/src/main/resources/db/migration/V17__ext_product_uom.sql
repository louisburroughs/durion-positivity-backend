-- #1413 (ADR-0055): per-product quantity divisibility on the work-order part path.
--
-- A part quantity is integral unless the product's BASE unit-of-measure row declares a non-zero
-- precision_scale. pos-catalog owns that declaration; this module needs it locally to answer the
-- question at estimate-item entry and part issue, so it is replicated from catalog.product.updated
-- facts rather than read synchronously across the domain wall (ADR-0044 R1/R3, §6). The same
-- replica already exists in pos-inventory (V11) and pos-order (V15); this is the third reader,
-- keyed and typed identically to both.
--
-- Additive only: no existing table, column, payload or contract is touched. Widening the ledger
-- and reservation chain to carry a decimal quantity is a later stage (#1414).
--
-- Every fact carries the product's full conversion set, so rows are replaced wholesale per fact
-- rather than merged — a UoM the owner retired must disappear here too, or a product would keep
-- being judged against a scale catalog no longer publishes.
--
-- The table is expected to be empty for the foreseeable future: nothing seeds product_uom
-- platform-wide. A product with no row here resolves to scale 0, which is exactly today's
-- behaviour, and that default is what makes installing the rule safe ahead of any seeding.

CREATE TABLE ext_product_uom (
    product_id       uuid NOT NULL,
    uom_code         varchar(32) NOT NULL,
    uom_type         varchar(16),
    factor_to_base   numeric(20, 6) NOT NULL,
    precision_scale  integer NOT NULL,
    -- Catalog stamps the product's updatedAt epoch millis on the fact envelope; carried per row so
    -- a redelivered older fact cannot roll a product's declaration backwards. Kept on the row
    -- rather than in a product-level anchor table because the set is replaced wholesale anyway.
    aggregate_version bigint NOT NULL,
    CONSTRAINT ext_product_uom_pkey PRIMARY KEY (product_id, uom_code)
);

-- The divisibility lookup is by (product, BASE) on every part-quantity check, so it must not be a
-- primary-key prefix scan that still has to filter every one of the product's package UoMs.
CREATE INDEX idx_ext_product_uom_product_type ON ext_product_uom (product_id, uom_type);
