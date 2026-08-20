-- =============================================================
-- R__seed_reference_catalog_5_uom.sql
-- product_uom seed — per-product divisibility and purchase units
--
-- First rows in this table platform-wide (ADR-0055 / #1417 / #1365).
-- Rules (see also the convention block in R__seed_reference_catalog_2_products.sql):
--   * Only products whose stock is genuinely divisible get a BASE row with
--     precision_scale > 0. Every product with no rows here defaults to
--     scale 0 (integral) — that default is load-bearing; do not seed
--     BASE/scale-0 rows for ordinary packaged SKUs just to be explicit.
--   * The BASE row's uom_code MUST match product.unit_of_measure, and its
--     factor_to_base MUST be 1 (ProductUomType.BASE contract).
--   * product_id is resolved by SKU because product ids are generated
--     (gen_random_uuid()); never hardcode product uuids here.
--
-- PARK-387TC-4-FT — Parker Hydraulic Hose 387TC-4, cut to length:
--   * BASE FT at precision_scale 2: dispensed lengths are recorded to
--     hundredths of a foot (3.5 ft, 12.25 ft). This is the first product
--     for which the ADR-0055 chain accepts fractional quantities.
--   * PURCHASE ROLL, factor 50: purchased as sealed 50-ft reels, whole
--     reels only (precision_scale 0 on the purchase document unit).
-- =============================================================
SET TIME ZONE 'UTC';

INSERT INTO product_uom (id, product_id, uom_code, uom_type, factor_to_base, precision_scale, created_at, updated_at)
SELECT '01960040-0000-7000-8000-000000000001'::uuid, p.id, 'FT', 'BASE', 1::numeric, 2, NOW(), NOW()
FROM product p WHERE p.sku = 'PARK-387TC-4-FT'
ON CONFLICT (product_id, uom_code) DO UPDATE SET
    uom_type = EXCLUDED.uom_type,
    factor_to_base = EXCLUDED.factor_to_base,
    precision_scale = EXCLUDED.precision_scale,
    updated_at = NOW();

INSERT INTO product_uom (id, product_id, uom_code, uom_type, factor_to_base, precision_scale, created_at, updated_at)
SELECT '01960040-0000-7000-8000-000000000002'::uuid, p.id, 'ROLL', 'PURCHASE', 50::numeric, 0, NOW(), NOW()
FROM product p WHERE p.sku = 'PARK-387TC-4-FT'
ON CONFLICT (product_id, uom_code) DO UPDATE SET
    uom_type = EXCLUDED.uom_type,
    factor_to_base = EXCLUDED.factor_to_base,
    precision_scale = EXCLUDED.precision_scale,
    updated_at = NOW();
