-- CAP-322 (#1312): local replica of pos-catalog product identity codes (ADR-0044 R3).
--
-- Supplier stock hints arrive carrying a vendor's EAN and no product id, so tying one to a product
-- is a cross-domain read of catalog-owned identity. ADR-0044 R1 forbids the synchronous call that
-- would otherwise answer it, and R3 makes a local, event-fed replica the sanctioned read path. This
-- table is that replica: written only by the catalog.events.v1 consumer, never by anything else.
--
-- The same table under the same name already exists in pos-supplier (its V9, CAP-318 #1224), which
-- resolves PRICAT lines the identical way. Two modules each keeping their own copy is the intended
-- shape — a replica belongs to the module that reads it, and neither may read the other's.
--
-- One row per product: a pos-catalog product carries at most one typed code. A product whose code
-- is cleared keeps its row with a NULL code, so "this product has no code" stays distinguishable
-- from "this module has never heard of this product" — the resolver reports those differently.
--
-- Written to be H2(MODE=PostgreSQL)-compatible, matching pos-supplier's copy.
CREATE TABLE ext_product_code (
    product_id uuid NOT NULL,
    code_type character varying(16),
    code character varying(64),
    aggregate_version bigint DEFAULT 0 NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,

    CONSTRAINT pk_ext_product_code PRIMARY KEY (product_id),
    CONSTRAINT ck_ext_product_code_type CHECK (code_type IS NULL OR code_type IN ('EAN', 'UPC'))
);

-- The resolver's only query: code -> product. Deliberately NOT unique. pos-catalog constrains
-- (code_type, code) at the source (#1232), so a second row here is a replication defect rather than
-- a catalog state; the resolver surfaces it as an ambiguity it refuses to guess at, which a unique
-- index would instead turn into a consumer crash loop on an event we cannot reject.
CREATE INDEX idx_ext_product_code_lookup ON ext_product_code (code_type, code);
