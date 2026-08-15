-- CAP-320 #1334: per-product UoM conversion replica, so pos-order can convert a purchase-order
-- line keyed in a document UoM ("1 CASE") to the product's base quantity.
--
-- The conversion belongs where the ordering happens: what a buyer keys is a commercial decision,
-- and the order is what records it. pos-inventory carried this replica while it held the
-- aggregate; the aggregate moving means the replica has to exist on both sides, each module
-- reading catalog's facts for its own purposes (ADR-0044 R3) rather than one asking the other.
--
-- Every catalog.product.updated fact carries the product's full conversion set, so these rows are
-- replaced wholesale per fact rather than merged — a UoM the owner retired must disappear here
-- too, or a line keyed in it would keep converting against a factor catalog no longer publishes.

CREATE TABLE ext_product_uom (
    product_id       uuid NOT NULL,
    uom_code         varchar(32) NOT NULL,
    uom_type         varchar(16),
    factor_to_base   numeric(20, 6) NOT NULL,
    precision_scale  integer NOT NULL,
    CONSTRAINT ext_product_uom_pkey PRIMARY KEY (product_id, uom_code)
);

-- The base UoM the factors above convert into. Without it the identity path (a line keyed in the
-- product's own base UoM) cannot be recognised, and every such line would look like a missing
-- conversion.
ALTER TABLE ext_product ADD COLUMN base_uom varchar(32);
