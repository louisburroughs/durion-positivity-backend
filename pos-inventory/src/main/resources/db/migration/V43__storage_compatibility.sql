-- Physical storage compatibility matrix (issue #1514).
--
-- WHAT THIS ANSWERS. "May an item of this catalog class physically go in a location of this storage
-- class?" Nothing in the model answered it before: destination eligibility was decided by
-- PutawayValidationServiceImpl requiring an (itemSKU, locationId) replenishment-policy row to
-- exist, which meant a brand-new SKU could never be put away anywhere (the bug in #1514) while a
-- tire could be put away into oil storage as long as somebody had written a policy row for it.
-- Replenishment policies are slotting targets for the restock scan; they are not bin physics.
--
-- SEED TIER. Tier 1 Flyway per docs/DATA_SEED_STRATEGY.md §2, and it satisfies all three gates:
--   (a) environment-invariant — the catalog category ids are themselves Flyway-seeded and identical
--       in alpha and prod (pos-catalog R__seed_reference_catalog.sql), and "a tire belongs on a tire
--       rack" is not demo data;
--   (b) crosses no domain wall — the table is private to pos-inventory, is published on no fact
--       topic and is projected into no other service's ext_* replica;
--   (c) no event-audited lifecycle — it is never created through an API, so no @EmitEvent audit
--       event is being skipped.
-- Putaway *rules*, by contrast, fail (a) and (c) and therefore stay out of Flyway and enter through
-- the CRUD endpoint / CSV fixture pack.
--
-- KEYED ON IDS, NOT NAMES. Category names are operator-facing labels that a rename changes; the
-- ids are the stable key, and pos-inventory only ever receives category *names* as un-refreshed
-- snapshots on product facts (catalog publishes product facts, not category facts, so a rename
-- needs a product replay). Matching on the name would silently stop matching after a rename.
--
-- SUBCATEGORY OVERRIDES REPLACE, THEY DO NOT ADD. When any SUBCATEGORY row exists for an item's
-- subcategory, that set is authoritative and the parent CATEGORY rows are ignored. This is the
-- whole reason SUBCATEGORY outranks CATEGORY: `Batteries` is a subcategory of `Electrical System`,
-- and a battery must NOT inherit its parent's SMALL_PARTS_BIN permission.
--
-- STAGING / QUARANTINE ARE ABSENT BY DESIGN. They are putaway *sources*, not destinations, so no
-- row may name one and the CHECK constraint below makes that structural rather than conventional.
-- The validator refuses them explicitly so the operator gets a reason, not a bare "no match".
--
-- GENERAL is not enumerated exhaustively here: it is the permissive default and the validator
-- accepts it for every category (see StorageCompatibilityEvaluator). The GENERAL rows that do
-- appear below are the matrix as tabulated in the #1514 contract, kept verbatim so the seed and the
-- contract can be diffed against each other.
--
-- No created_at/updated_at columns: this is a static lookup table written only by Flyway and read
-- only by the evaluator. There is no lifecycle to audit, and unused audit columns would imply one.
--
-- H2(MODE=PostgreSQL)- and PostgreSQL-compatible: CHECK constraints use the `IN (...)` form rather
-- than the Postgres-dump `(col)::text = ANY ((ARRAY[...])::text[])` idiom that
-- V1__baseline_inventory_schema.sql carries, because that idiom is a syntax error on H2 and the
-- `dev` profile runs H2. StorageCompatibilityMigrationTest runs this file against H2.

CREATE TABLE storage_compatibility (
    compatibility_id uuid NOT NULL,
    match_level character varying(20) NOT NULL,
    catalog_ref_id uuid NOT NULL,
    storage_category_code character varying(30) NOT NULL,
    requires_containment boolean DEFAULT FALSE NOT NULL,
    CONSTRAINT storage_compatibility_pkey PRIMARY KEY (compatibility_id),
    CONSTRAINT storage_compatibility_uk UNIQUE (match_level, catalog_ref_id, storage_category_code),
    CONSTRAINT storage_compatibility_match_level_check
        CHECK (match_level IN ('CATEGORY', 'SUBCATEGORY')),
    CONSTRAINT storage_compatibility_storage_category_code_check
        CHECK (storage_category_code IN ('TIRE_RACK', 'OIL_STORAGE', 'BATTERY_RACK',
                                         'SMALL_PARTS_BIN', 'BULK_FLOOR', 'GENERAL'))
);

-- Looked up by (level, ref id) for one item at a time; the unique constraint already covers the
-- leading columns, so no additional index is needed.

-- ---------------------------------------------------------------------------------------------
-- CATEGORY level — the 12 seeded catalog categories (01960030-0000-7000-8000-0000000000NN).
-- requires_containment is TRUE exactly where the accepted storage class is one of the two
-- containment-bearing ones (BATTERY_RACK, OIL_STORAGE): those are not interchangeable with a plain
-- shelf, and a destination coded as one but not declaring hazard_containment is refused.
-- ---------------------------------------------------------------------------------------------
INSERT INTO storage_compatibility
    (compatibility_id, match_level, catalog_ref_id, storage_category_code, requires_containment)
VALUES
    -- Tires & Wheels
    ('01960033-0000-7000-8000-000000000001', 'CATEGORY', '01960030-0000-7000-8000-000000000001', 'TIRE_RACK',       FALSE),
    ('01960033-0000-7000-8000-000000000002', 'CATEGORY', '01960030-0000-7000-8000-000000000001', 'BULK_FLOOR',      FALSE),
    -- Engine Parts
    ('01960033-0000-7000-8000-000000000003', 'CATEGORY', '01960030-0000-7000-8000-000000000002', 'SMALL_PARTS_BIN', FALSE),
    ('01960033-0000-7000-8000-000000000004', 'CATEGORY', '01960030-0000-7000-8000-000000000002', 'GENERAL',         FALSE),
    -- Brake System
    ('01960033-0000-7000-8000-000000000005', 'CATEGORY', '01960030-0000-7000-8000-000000000003', 'SMALL_PARTS_BIN', FALSE),
    ('01960033-0000-7000-8000-000000000006', 'CATEGORY', '01960030-0000-7000-8000-000000000003', 'GENERAL',         FALSE),
    -- Electrical System (Batteries is overridden at subcategory level below)
    ('01960033-0000-7000-8000-000000000007', 'CATEGORY', '01960030-0000-7000-8000-000000000004', 'SMALL_PARTS_BIN', FALSE),
    ('01960033-0000-7000-8000-000000000008', 'CATEGORY', '01960030-0000-7000-8000-000000000004', 'GENERAL',         FALSE),
    -- Drivetrain & Transmission
    ('01960033-0000-7000-8000-000000000009', 'CATEGORY', '01960030-0000-7000-8000-000000000005', 'SMALL_PARTS_BIN', FALSE),
    ('01960033-0000-7000-8000-00000000000a', 'CATEGORY', '01960030-0000-7000-8000-000000000005', 'BULK_FLOOR',      FALSE),
    ('01960033-0000-7000-8000-00000000000b', 'CATEGORY', '01960030-0000-7000-8000-000000000005', 'GENERAL',         FALSE),
    -- Suspension & Steering
    ('01960033-0000-7000-8000-00000000000c', 'CATEGORY', '01960030-0000-7000-8000-000000000006', 'SMALL_PARTS_BIN', FALSE),
    ('01960033-0000-7000-8000-00000000000d', 'CATEGORY', '01960030-0000-7000-8000-000000000006', 'BULK_FLOOR',      FALSE),
    ('01960033-0000-7000-8000-00000000000e', 'CATEGORY', '01960030-0000-7000-8000-000000000006', 'GENERAL',         FALSE),
    -- Fluids & Chemicals — OIL_STORAGE is containment-bearing
    ('01960033-0000-7000-8000-00000000000f', 'CATEGORY', '01960030-0000-7000-8000-000000000007', 'OIL_STORAGE',     TRUE),
    ('01960033-0000-7000-8000-000000000010', 'CATEGORY', '01960030-0000-7000-8000-000000000007', 'BULK_FLOOR',      FALSE),
    -- Filters
    ('01960033-0000-7000-8000-000000000011', 'CATEGORY', '01960030-0000-7000-8000-000000000008', 'SMALL_PARTS_BIN', FALSE),
    ('01960033-0000-7000-8000-000000000012', 'CATEGORY', '01960030-0000-7000-8000-000000000008', 'GENERAL',         FALSE),
    -- Exhaust System
    ('01960033-0000-7000-8000-000000000013', 'CATEGORY', '01960030-0000-7000-8000-000000000009', 'BULK_FLOOR',      FALSE),
    ('01960033-0000-7000-8000-000000000014', 'CATEGORY', '01960030-0000-7000-8000-000000000009', 'GENERAL',         FALSE),
    -- HVAC & Climate
    ('01960033-0000-7000-8000-000000000015', 'CATEGORY', '01960030-0000-7000-8000-00000000000a', 'SMALL_PARTS_BIN', FALSE),
    ('01960033-0000-7000-8000-000000000016', 'CATEGORY', '01960030-0000-7000-8000-00000000000a', 'GENERAL',         FALSE),
    -- Body & Lighting
    ('01960033-0000-7000-8000-000000000017', 'CATEGORY', '01960030-0000-7000-8000-00000000000b', 'SMALL_PARTS_BIN', FALSE),
    ('01960033-0000-7000-8000-000000000018', 'CATEGORY', '01960030-0000-7000-8000-00000000000b', 'GENERAL',         FALSE),
    -- Heavy Equipment & Hydraulics
    ('01960033-0000-7000-8000-000000000019', 'CATEGORY', '01960030-0000-7000-8000-00000000000c', 'BULK_FLOOR',      FALSE),
    ('01960033-0000-7000-8000-00000000001a', 'CATEGORY', '01960030-0000-7000-8000-00000000000c', 'GENERAL',         FALSE);

-- ---------------------------------------------------------------------------------------------
-- SUBCATEGORY level — overrides only where the subcategory's physical nature differs from what
-- its parent category implies (01960031-0000-7000-8000-0000000000NN).
--
-- The fluid subcategories under Fluids & Chemicals (19-1c) need no override: they already inherit
-- OIL_STORAGE/BULK_FLOOR from the parent. ATF & Gear Oil (12) is the exception, because it is a
-- fluid filed under Drivetrain & Transmission rather than Fluids & Chemicals — see its row below.
-- Tire subcategories (01-06) likewise inherit correctly from Tires & Wheels.
-- ---------------------------------------------------------------------------------------------
INSERT INTO storage_compatibility
    (compatibility_id, match_level, catalog_ref_id, storage_category_code, requires_containment)
VALUES
    -- Batteries: BATTERY_RACK only, and only one that declares containment. This narrower row is
    -- what stops a battery inheriting Electrical System's SMALL_PARTS_BIN permission.
    ('01960033-0000-7000-8000-00000000001b', 'SUBCATEGORY', '01960031-0000-7000-8000-00000000000e', 'BATTERY_RACK', TRUE),
    -- Hydraulic Cylinders & Hoses: floor stock, never a small-parts bin, and GENERAL is dropped
    -- from the parent's set because these do not fit a shelf.
    ('01960033-0000-7000-8000-00000000001c', 'SUBCATEGORY', '01960031-0000-7000-8000-000000000027', 'BULK_FLOOR',   FALSE),
    -- ATF & Gear Oil: a bulk fluid whose parent category is Drivetrain & Transmission (05), not
    -- Fluids & Chemicals (07). Without this override ATF inherits Drivetrain's entirely uncontained
    -- set (SMALL_PARTS_BIN / BULK_FLOOR / GENERAL) and OIL_STORAGE is absent from it — so a gallon
    -- of ATF would be accepted into a small-parts bin and refused from oil storage, the exact
    -- inversion of what the shop floor needs. The seeded products that hit this are VALV-ATF-ML-QT,
    -- VALV-ATF-ML-GA, PRES-AF5060-6 and PRES-AF5063
    -- (pos-catalog R__seed_reference_catalog_2_products.sql:109-112).
    --
    -- BULK_FLOOR stays uncontained, mirroring how Fluids & Chemicals is expressed: a quart on a
    -- bulk floor is ordinary, so containment is required of the dedicated fluid location rather
    -- than of the item everywhere.
    ('01960033-0000-7000-8000-00000000001d', 'SUBCATEGORY', '01960031-0000-7000-8000-000000000012', 'OIL_STORAGE',  TRUE),
    ('01960033-0000-7000-8000-00000000001e', 'SUBCATEGORY', '01960031-0000-7000-8000-000000000012', 'BULK_FLOOR',   FALSE);
