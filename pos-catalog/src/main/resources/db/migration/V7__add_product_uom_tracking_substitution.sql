-- #1023 (inventory Odoo-parity Story X1): product contract additions owned by pos-catalog and
-- replicated to pos-inventory via catalog.product.updated events (ADR-0044):
--   1. tracking_level on product (NONE | LOT | SERIAL, default NONE)
--   2. product_uom — per-product purchase/pack UoMs with factor to the base UoM + precision scale
--   3. substitution_group / substitution_group_member — interchangeable-product groups
--      (a product belongs to at most one group)

ALTER TABLE product ADD COLUMN tracking_level character varying(16) DEFAULT 'NONE' NOT NULL;
ALTER TABLE product ADD CONSTRAINT product_tracking_level_check
    CHECK (((tracking_level)::text = ANY ((ARRAY['NONE'::character varying, 'LOT'::character varying, 'SERIAL'::character varying])::text[])));

CREATE TABLE product_uom (
    id uuid NOT NULL,
    product_id uuid NOT NULL,
    uom_code character varying(32) NOT NULL,
    uom_type character varying(16) NOT NULL,
    factor_to_base numeric(20,6) NOT NULL,
    precision_scale integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT product_uom_pkey PRIMARY KEY (id),
    CONSTRAINT uk_product_uom_product_code UNIQUE (product_id, uom_code),
    CONSTRAINT product_uom_uom_type_check
        CHECK (((uom_type)::text = ANY ((ARRAY['BASE'::character varying, 'PURCHASE'::character varying, 'PACK'::character varying, 'OTHER'::character varying])::text[]))),
    CONSTRAINT fk_product_uom_product FOREIGN KEY (product_id) REFERENCES product(id)
);
CREATE INDEX idx_product_uom_product ON product_uom (product_id);

CREATE TABLE substitution_group (
    id uuid NOT NULL,
    name character varying(128) NOT NULL,
    notes character varying(512),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT substitution_group_pkey PRIMARY KEY (id)
);

CREATE TABLE substitution_group_member (
    id uuid NOT NULL,
    group_id uuid NOT NULL,
    product_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT substitution_group_member_pkey PRIMARY KEY (id),
    CONSTRAINT uk_substitution_group_member_product UNIQUE (product_id),
    CONSTRAINT fk_substitution_group_member_group FOREIGN KEY (group_id) REFERENCES substitution_group(id),
    CONSTRAINT fk_substitution_group_member_product FOREIGN KEY (product_id) REFERENCES product(id)
);
CREATE INDEX idx_substitution_group_member_group ON substitution_group_member (group_id);
