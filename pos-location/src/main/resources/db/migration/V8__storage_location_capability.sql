-- Storage-location putaway capability (issue #1514).
--
-- `type` stays exactly what it was: the physical topology (FLOOR/SHELF/BIN/CAGE/TRUCK).
-- storage_category_code is the orthogonal question of what the location is fit to *hold*, so
-- category-based putaway can route tires to tire racks and oil to oil storage without inventing
-- a parallel type hierarchy. A tire rack and a bulk pallet area are both FLOOR; only one of them
-- should receive tires.
--
-- storage_category_code is nullable and no rows are backfilled: a location that has never
-- declared a capability keeps NULL, and every read path resolves NULL to GENERAL — the
-- permissive default that accepts every catalog category (StorageCategory.orDefault). Keeping
-- the column nullable is what makes this a pure add: no existing row changes meaning, and
-- "never declared" stays distinguishable from "explicitly GENERAL".
--
-- hazard_containment and allow_new_product back the compatibility matrix pos-inventory
-- evaluates: containment is what BATTERY_RACK and OIL_STORAGE require, and allow_new_product
-- decides whether a destination may accumulate a product it is not already holding. Both are
-- NOT NULL with a database default, so existing rows land on the permissive values (no
-- containment, mixing allowed) that match how they behave today.
--
-- Deliberate style deviation: V1__baseline_location_schema.sql:33 carries the Postgres-dump
-- CHECK idiom `(col)::text = ANY ((ARRAY['A'::character varying])::text[])`, which is a syntax
-- error on H2 — and the `dev` profile runs H2. These constraints therefore use the `IN (...)`
-- form that the repo's hand-written migrations use (e.g. pos-accounting V16/V18), which parses
-- identically on both engines. StorageLocationCapabilityMigrationTest runs this file against H2
-- to keep that true.

ALTER TABLE storage_location ADD COLUMN storage_category_code character varying(30);

ALTER TABLE storage_location ADD COLUMN hazard_containment boolean DEFAULT FALSE NOT NULL;

ALTER TABLE storage_location ADD COLUMN allow_new_product character varying(30) DEFAULT 'MIXED' NOT NULL;

-- NULL is explicitly permitted: it is the "capability never declared" state, not a violation.
ALTER TABLE storage_location ADD CONSTRAINT storage_location_storage_category_code_check
    CHECK (storage_category_code IS NULL OR storage_category_code IN
        ('TIRE_RACK', 'OIL_STORAGE', 'BATTERY_RACK', 'SMALL_PARTS_BIN',
         'BULK_FLOOR', 'STAGING', 'QUARANTINE', 'GENERAL'));

ALTER TABLE storage_location ADD CONSTRAINT storage_location_allow_new_product_check
    CHECK (allow_new_product IN ('MIXED', 'SAME_PRODUCT_ONLY', 'EMPTY_ONLY'));
