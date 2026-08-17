-- CAP-320 #1347: vendor's own article code per (supplierRef, product), replicated from
-- catalog.supplier_article_code.updated. Lets purchase-order transmission name a line to a
-- non-EAN vendor. Keyed by supplier_ref rather than vendor_profile_id: PurchaseOrderEntity carries
-- only the alias, never the vendor profile id (see ExtSupplierArticleCode's class javadoc).

CREATE TABLE ext_supplier_article_code (
    id uuid NOT NULL,

    supplier_ref character varying(100) NOT NULL,
    vendor_profile_id uuid NOT NULL,
    product_id uuid NOT NULL,
    supplier_article_code character varying(64) NOT NULL,

    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,

    CONSTRAINT pk_ext_supplier_article_code PRIMARY KEY (id),
    CONSTRAINT uk_ext_supplier_article_code_ref_product UNIQUE (supplier_ref, product_id)
);

CREATE INDEX idx_ext_supplier_article_code_product ON ext_supplier_article_code (product_id);
