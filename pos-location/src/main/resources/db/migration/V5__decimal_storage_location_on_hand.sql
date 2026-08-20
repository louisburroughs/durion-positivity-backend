-- ADR-0055 stage 2 (#1414): the on-hand facts this replica mirrors are now decimal.
--
-- StorageLocationOnHandUpdatedV1 carries a BigDecimal quantity because it sums the inventory
-- ledger, which widened to numeric(19,4) so that a product whose catalog declaration permits
-- decimals can be stocked at its real quantity. This table backs the "no decommission while
-- stocked" guard, and that guard has to see a partial drum as stock rather than as zero.
--
-- Pre-production widening: numeric(19,4) holds every value the integer column could, so the
-- implicit cast preserves existing rows exactly.
ALTER TABLE ext_storage_location_on_hand
    ALTER COLUMN on_hand_quantity TYPE numeric(19,4);
