-- #1536: subcategory was (id, name, created_at, updated_at) and carried no parent, while
-- product.category_id and product.subcategory_id were two independent nullable foreign keys.
-- Nothing prevented a product from holding category 'Tires & Wheels' together with subcategory
-- 'Batteries': both ids resolved, so the contradiction was invisible to every reader. pos-inventory's
-- PutawayRuleMatcher resolves putaway rules in SKU > SUBCATEGORY > CATEGORY > ANY precedence and
-- matches each level independently on its id, so a contradictory pair routes stock confidently to the
-- wrong zone and never reports an error.
--
-- The mechanism is a schema change rather than an application-level validation rule because the
-- precedence order above only means anything if a subcategory really is contained by a category.
-- Making subcategory.category_id a NOT NULL foreign key gives every subcategory exactly one parent,
-- which turns the contradictory pair from "allowed but wrong" into unrepresentable — the product's
-- category becomes a derivable function of its subcategory, and no future write path can bypass it.
--
-- Existing parents are derived from the products that already reference both columns (Stage A),
-- backfilled from the seeded reference taxonomy for subcategories that no product uses (Stage B),
-- and the migration refuses to guess for anything still unresolved (Stage C).
--
-- Postgres-only syntax (DISTINCT ON, DO $$ ... $$, IS DISTINCT FROM) is deliberate and safe here:
-- pos-catalog's tests run with Flyway disabled against H2 (ddl-auto: create-drop), so this migration
-- is never exercised by the test suite and only ever executes against PostgreSQL.

ALTER TABLE subcategory ADD COLUMN category_id uuid;

-- Stage A: derive each subcategory's parent from the products that already pair the two ids.
-- Semantics: group the existing (subcategory_id, category_id) pairs and count them, then keep one
-- row per subcategory — the most frequently paired category wins, and an exact tie breaks on the
-- lowest category_id so the outcome does not depend on physical row order or plan choice.
-- DISTINCT ON keeps the first row of each subcategory_id group under the ORDER BY, and the ORDER BY
-- may reference count(*) because DISTINCT is applied after grouping.
WITH derived_parent AS (
    SELECT DISTINCT ON (p.subcategory_id) p.subcategory_id, p.category_id
    FROM product p
    WHERE p.subcategory_id IS NOT NULL AND p.category_id IS NOT NULL
    GROUP BY p.subcategory_id, p.category_id
    ORDER BY p.subcategory_id, count(*) DESC, p.category_id ASC
)
UPDATE subcategory s
SET category_id = derived_parent.category_id
FROM derived_parent
WHERE s.id = derived_parent.subcategory_id
  AND s.category_id IS NULL;

-- Stage B: the seeded reference taxonomy, for any seeded subcategory that Stage A could not reach
-- because no product references it. These 40 pairs are the same ones declared by
-- R__seed_reference_catalog.sql, and AlphaFixtureCategoryNamesResolveTest asserts the two agree.
WITH seeded_parent (subcategory_id, category_id) AS (
    VALUES
      ('01960031-0000-7000-8000-000000000001'::uuid, '01960030-0000-7000-8000-000000000001'::uuid), -- Commercial Truck Tires -> Tires & Wheels
      ('01960031-0000-7000-8000-000000000002', '01960030-0000-7000-8000-000000000001'), -- Light Truck & SUV Tires -> Tires & Wheels
      ('01960031-0000-7000-8000-000000000003', '01960030-0000-7000-8000-000000000001'), -- Passenger Car Tires -> Tires & Wheels
      ('01960031-0000-7000-8000-000000000004', '01960030-0000-7000-8000-000000000001'), -- OTR & Off-Highway Tires -> Tires & Wheels
      ('01960031-0000-7000-8000-000000000005', '01960030-0000-7000-8000-000000000001'), -- Trailer Tires -> Tires & Wheels
      ('01960031-0000-7000-8000-000000000006', '01960030-0000-7000-8000-000000000001'), -- Forklift & Industrial Tires -> Tires & Wheels
      ('01960031-0000-7000-8000-000000000007', '01960030-0000-7000-8000-000000000002'), -- Spark Plugs & Ignition -> Engine Parts
      ('01960031-0000-7000-8000-000000000008', '01960030-0000-7000-8000-000000000002'), -- Belts & Tensioners -> Engine Parts
      ('01960031-0000-7000-8000-000000000009', '01960030-0000-7000-8000-000000000002'), -- Hoses & Cooling -> Engine Parts
      ('01960031-0000-7000-8000-00000000000a', '01960030-0000-7000-8000-000000000002'), -- Engine Gaskets & Seals -> Engine Parts
      ('01960031-0000-7000-8000-00000000000b', '01960030-0000-7000-8000-000000000003'), -- Brake Pads & Shoes -> Brake System
      ('01960031-0000-7000-8000-00000000000c', '01960030-0000-7000-8000-000000000003'), -- Brake Rotors & Drums -> Brake System
      ('01960031-0000-7000-8000-00000000000d', '01960030-0000-7000-8000-000000000003'), -- Brake Hardware & Calipers -> Brake System
      ('01960031-0000-7000-8000-00000000000e', '01960030-0000-7000-8000-000000000004'), -- Batteries -> Electrical System
      ('01960031-0000-7000-8000-00000000000f', '01960030-0000-7000-8000-000000000004'), -- Alternators & Starters -> Electrical System
      ('01960031-0000-7000-8000-000000000010', '01960030-0000-7000-8000-000000000004'), -- Fuses & Relays -> Electrical System
      ('01960031-0000-7000-8000-000000000011', '01960030-0000-7000-8000-00000000000b'), -- Wiper Blades -> Body & Lighting
      ('01960031-0000-7000-8000-000000000012', '01960030-0000-7000-8000-000000000005'), -- ATF & Gear Oil -> Drivetrain & Transmission
      ('01960031-0000-7000-8000-000000000013', '01960030-0000-7000-8000-000000000005'), -- CV Axles & Driveshafts -> Drivetrain & Transmission
      ('01960031-0000-7000-8000-000000000014', '01960030-0000-7000-8000-000000000005'), -- Clutch Components -> Drivetrain & Transmission
      ('01960031-0000-7000-8000-000000000015', '01960030-0000-7000-8000-000000000006'), -- Shocks & Struts -> Suspension & Steering
      ('01960031-0000-7000-8000-000000000016', '01960030-0000-7000-8000-000000000006'), -- Ball Joints & Control Arms -> Suspension & Steering
      ('01960031-0000-7000-8000-000000000017', '01960030-0000-7000-8000-000000000006'), -- Wheel Bearings & Hubs -> Suspension & Steering
      ('01960031-0000-7000-8000-000000000018', '01960030-0000-7000-8000-000000000006'), -- Steering Components -> Suspension & Steering
      ('01960031-0000-7000-8000-000000000019', '01960030-0000-7000-8000-000000000007'), -- Motor Oil -> Fluids & Chemicals
      ('01960031-0000-7000-8000-00000000001a', '01960030-0000-7000-8000-000000000007'), -- Coolant & Antifreeze -> Fluids & Chemicals
      ('01960031-0000-7000-8000-00000000001b', '01960030-0000-7000-8000-000000000007'), -- Brake Fluid & Power Steering -> Fluids & Chemicals
      ('01960031-0000-7000-8000-00000000001c', '01960030-0000-7000-8000-000000000007'), -- Specialty Fluids & Additives -> Fluids & Chemicals
      ('01960031-0000-7000-8000-00000000001d', '01960030-0000-7000-8000-000000000008'), -- Oil Filters -> Filters
      ('01960031-0000-7000-8000-00000000001e', '01960030-0000-7000-8000-000000000008'), -- Air Filters -> Filters
      ('01960031-0000-7000-8000-00000000001f', '01960030-0000-7000-8000-000000000008'), -- Fuel Filters -> Filters
      ('01960031-0000-7000-8000-000000000020', '01960030-0000-7000-8000-000000000008'), -- Cabin Air Filters -> Filters
      ('01960031-0000-7000-8000-000000000021', '01960030-0000-7000-8000-000000000009'), -- Mufflers & Exhaust Pipes -> Exhaust System
      ('01960031-0000-7000-8000-000000000022', '01960030-0000-7000-8000-000000000009'), -- Catalytic Converters & O2 Sensors -> Exhaust System
      ('01960031-0000-7000-8000-000000000023', '01960030-0000-7000-8000-00000000000a'), -- A/C Compressors & Components -> HVAC & Climate
      ('01960031-0000-7000-8000-000000000024', '01960030-0000-7000-8000-00000000000a'), -- Heater Cores & Blower Motors -> HVAC & Climate
      ('01960031-0000-7000-8000-000000000025', '01960030-0000-7000-8000-00000000000b'), -- Lighting & Bulbs -> Body & Lighting
      ('01960031-0000-7000-8000-000000000026', '01960030-0000-7000-8000-00000000000b'), -- Mirrors & Body Hardware -> Body & Lighting
      ('01960031-0000-7000-8000-000000000027', '01960030-0000-7000-8000-00000000000c'), -- Hydraulic Cylinders & Hoses -> Heavy Equipment & Hydraulics
      ('01960031-0000-7000-8000-000000000028', '01960030-0000-7000-8000-00000000000c')  -- Heavy Equipment Filters -> Heavy Equipment & Hydraulics
)
UPDATE subcategory s
SET category_id = seeded_parent.category_id
FROM seeded_parent
WHERE s.id = seeded_parent.subcategory_id
  AND s.category_id IS NULL;

-- Stage C: stop rather than invent a parent. A subcategory that neither any product nor the seeded
-- taxonomy can classify is data the migration has no evidence about, and picking an arbitrary
-- category here would recreate exactly the silent mis-routing this issue exists to remove.
DO $$
DECLARE
    unresolved text;
BEGIN
    SELECT string_agg(s.id::text || ' (' || coalesce(s.name, '<unnamed>') || ')', ', ' ORDER BY s.id)
    INTO unresolved
    FROM subcategory s
    WHERE s.category_id IS NULL;

    IF unresolved IS NOT NULL THEN
        RAISE EXCEPTION 'V16 cannot determine a parent category for subcategory: %', unresolved
            USING HINT = 'No product references these subcategories and they are not in the seeded '
                'reference taxonomy. Assign a category_id manually, or delete the subcategory if it '
                'is unused, then re-run this migration.';
    END IF;
END $$;

ALTER TABLE subcategory ALTER COLUMN category_id SET NOT NULL;

-- No ON DELETE clause: deleting a category that still has subcategories must fail loudly rather
-- than cascade rows away or silently null out a column that is now NOT NULL.
ALTER TABLE subcategory ADD CONSTRAINT fk_subcategory_category FOREIGN KEY (category_id) REFERENCES category (id);

-- PostgreSQL does not index the referencing side of a foreign key automatically, and every
-- category-scoped read now has to walk subcategory -> category.
CREATE INDEX idx_subcategory_category_id ON subcategory (category_id);

-- Stage D: repair products whose stored category contradicts their subcategory's parent. The
-- subcategory is the more specific classification, so it wins and the category is realigned to its
-- parent. On a consistent environment this is a no-op.
--
-- This deliberately does NOT touch product.updated_at or product.version. Those feed the
-- aggregateVersion on catalog.events.v1, and a version bump with no corresponding published fact
-- would look like a regression to consumers' stale-event guards (see V15). The consequence is that
-- inventory replicas keep the old, contradictory category until the affected products are
-- republished — run the product fact replay procedure in docs/OPERATIONS_RUNBOOK.md after this
-- migration if it reports a non-zero row count.
UPDATE product p
SET category_id = s.category_id
FROM subcategory s
WHERE p.subcategory_id = s.id
  AND p.category_id IS DISTINCT FROM s.category_id;
