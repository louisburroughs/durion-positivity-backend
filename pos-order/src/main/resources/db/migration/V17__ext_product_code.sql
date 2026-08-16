-- CAP-320 #1330: the product's identity code, so a transmitted line can name an article the
-- vendor recognises.
--
-- Purchase-order lines carry skuId. The vendor has never heard of our ids and needs an EAN. The
-- code is catalog's to state, so it arrives on catalog.product.updated like every other product
-- fact rather than being fetched (ADR-0044 R1/R3).
--
-- A cleared code is stored as null rather than by deleting the row. "This product carries no code"
-- and "this module has never heard of this product" are different answers, and a buyer told the
-- wrong one looks in the wrong place: the first is a data gap in catalog, the second is a replica
-- that has not caught up and will fix itself.
CREATE TABLE ext_product_code (
    product_id        uuid PRIMARY KEY,
    code_type         varchar(16),
    code              varchar(64),
    aggregate_version bigint NOT NULL,
    updated_at        timestamp(6) with time zone NOT NULL
);

CREATE INDEX idx_ext_product_code_lookup ON ext_product_code (code_type, code);
