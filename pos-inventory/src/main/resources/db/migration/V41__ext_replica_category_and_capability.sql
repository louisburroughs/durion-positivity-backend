-- #1514 (category-based putaway): teach the inventory replicas the two halves of a putaway match.
--
-- Putaway rules match "what an item is" against "what a location is fit for". Neither half was
-- replicated here: ext_product knew a product's UoM, tracking level and substitution group but not
-- its category, and ext_storage_location knew a rack's shape but not what may be stored on it.
-- ADR-0044 R1 forbids asking pos-catalog or pos-location at match time, and R3 makes the local
-- event-fed replica the sanctioned read path — so both halves ride their existing facts
-- (catalog.product.updated, location.storage-location.updated) into these columns.
--
-- Every column is nullable: a replica mirrors its upstream, and a producer can legitimately publish
-- a row that carries none of this (an uncategorised product). Callers treat null as "unknown",
-- never as a default.
--
-- ROLLOUT: these columns are added empty and there is no backfill — by design, because the owning
-- data lives in another service and copying it in via SQL would bypass the fact that is its only
-- sanctioned carrier. Existing rows stay NULL until their aggregate is next published, so an
-- environment with existing inventory needs a deliberate republish of both feeds before
-- category-based putaway has anything to match on: pos-catalog's product fact replay and a
-- pos-location storage-location republish. Until then matching degrades to "no category resolved",
-- which is the pre-#1514 behaviour rather than a wrong answer.
--
-- Deliberately NO check constraints on the capability codes. The owning enums live in pos-location
-- and pos-catalog; a constraint here would let a value that is valid upstream crash this consumer
-- on an event it cannot reject, turning an additive owner-side change into a replication outage.
-- Validation belongs where the enum is defined.
--
-- Written to be H2(MODE=PostgreSQL)- and PostgreSQL-compatible: one column per ALTER statement,
-- no multi-column ADD, no IF NOT EXISTS.

-- ext_product: the item half. Both the id and the name snapshot are kept — the ids are the stable
-- match key for putaway rules, the names are what the SKU_CATEGORY-scoped sourcing and costing
-- config rows are authored against.
ALTER TABLE ext_product ADD COLUMN category_id uuid;
ALTER TABLE ext_product ADD COLUMN category_name character varying(255);
ALTER TABLE ext_product ADD COLUMN subcategory_id uuid;
ALTER TABLE ext_product ADD COLUMN subcategory_name character varying(255);

-- Rule matching walks SKU -> SUBCATEGORY -> CATEGORY, so both id columns are looked up by value.
CREATE INDEX idx_ext_product_category ON ext_product (category_id);
CREATE INDEX idx_ext_product_subcategory ON ext_product (subcategory_id);

-- ext_storage_location: the location half.
--   storage_category_code  what the location is fit for (pos-location's StorageCategory:
--                          TIRE_RACK, OIL_STORAGE, BATTERY_RACK, SMALL_PARTS_BIN, BULK_FLOOR,
--                          STAGING, QUARANTINE, GENERAL). GENERAL is the permissive default.
--   hazard_containment     whether the location provides containment for hazardous goods; the
--                          reason BATTERY_RACK and OIL_STORAGE are not interchangeable with a
--                          plain shelf.
--   allow_new_product      whether a putaway may introduce a product the location is not already
--                          holding: MIXED | SAME_PRODUCT_ONLY | EMPTY_ONLY.
ALTER TABLE ext_storage_location ADD COLUMN storage_category_code character varying(64);
ALTER TABLE ext_storage_location ADD COLUMN hazard_containment boolean;
ALTER TABLE ext_storage_location ADD COLUMN allow_new_product character varying(64);

-- Destination search is "locations in this site fit for this category", so the code is filtered on.
CREATE INDEX idx_ext_storage_location_storage_category ON ext_storage_location (storage_category_code);
