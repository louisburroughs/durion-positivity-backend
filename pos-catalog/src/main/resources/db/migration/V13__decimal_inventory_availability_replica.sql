-- ADR-0055 stage 2 (#1414): the availability facts this replica mirrors are now decimal.
--
-- InventoryAvailabilityUpdatedV1 carries BigDecimal quantities so that a product whose catalog
-- declaration (product_uom.precision_scale) permits decimals can be reported at its real
-- quantity. Every product declares scale 0 today, so no value stored here changes; what changes
-- is that a divisible one is no longer truncated on arrival.
--
-- Pre-production widening: numeric(19,4) holds every value the integer columns could, so the
-- implicit cast preserves existing rows exactly.
ALTER TABLE ext_inventory_availability
    ALTER COLUMN on_hand_quantity TYPE numeric(19,4),
    ALTER COLUMN allocated_quantity TYPE numeric(19,4),
    ALTER COLUMN available_to_promise_quantity TYPE numeric(19,4);
