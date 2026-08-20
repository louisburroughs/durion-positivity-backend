-- ADR-0055 stage 3 (#1415): a part line records the unit its quantity is expressed in, so
-- "4.5 QT" can be entered instead of an implicit bare number.
--
-- Null means the quantity is already in the product's base unit -- today's implicit assumption.
-- Existing rows have no uom_code and none needs backfilling: they read as null after this
-- migration and behave exactly as before it (pre-production, no data to migrate).
--
-- LABOR rows on estimate_item ALWAYS carry a null uom_code. quantity is shared between PART and
-- LABOR, but hours are not a product unit of measure and have no product_uom conversion row --
-- uom_code names a catalog conversion, and a LABOR row names no catalog product. The application
-- layer rejects a non-null uom_code on a LABOR row (400); the check constraint below enforces the
-- same rule at the data layer so it cannot be bypassed by a write that skips the service.
ALTER TABLE estimate_item
    ADD COLUMN uom_code varchar(32),
    ADD CONSTRAINT ck_estimate_item_labor_uom_null
        CHECK (item_type <> 'LABOR' OR uom_code IS NULL);

-- workorder_part.uom_code is snapshotted from estimate_item.uom_code at promotion, the same way
-- quantity itself is snapshotted -- see WorkorderServiceImpl. Every part promoted here is a PART
-- item (LABOR promotes to workorder_service, not workorder_part), so no equivalent check
-- constraint is needed on this table.
ALTER TABLE workorder_part
    ADD COLUMN uom_code varchar(32);
