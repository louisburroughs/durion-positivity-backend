-- CAP-320 #1347: current-state projection of the vendor's own article code per (vendor, product).
--
-- supplier_price_entry already carries supplier_article_code, but as append-only history with no
-- single "current" row queryable without a tie-break. This table is that projection, upserted from
-- the same PRICAT ingestion as supplier_price_entry, and feeds catalog.supplier_article_code.updated
-- for pos-order's purchase-order transmission (CAP-320 #1330).

CREATE TABLE supplier_article_code (
    id uuid NOT NULL,

    vendor_profile_id uuid NOT NULL,
    supplier_ref character varying(100) NOT NULL,
    product_id uuid NOT NULL,
    supplier_article_code character varying(64) NOT NULL,

    updated_at timestamp(6) with time zone NOT NULL,

    CONSTRAINT pk_supplier_article_code PRIMARY KEY (id),
    CONSTRAINT uk_supplier_article_code_vendor_product UNIQUE (vendor_profile_id, product_id)
);

CREATE INDEX idx_supplier_article_code_product ON supplier_article_code (product_id);
