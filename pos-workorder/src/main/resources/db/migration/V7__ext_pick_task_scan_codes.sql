-- #2217: the pick facade's resolve-scan endpoint needs to verify a mechanic's scanned barcode
-- against a human-readable code, without pos-workorder calling pos-catalog or pos-location
-- synchronously (ADR-0044 R1/R3, no new cross-module client). pos-inventory now carries these
-- codes on PickTaskUpdatedV1, so InventoryEventsListener projects them here.
--
-- Nullable and unbackfilled: a row replicated before this column existed carries null until its
-- next inventory.pick-task.updated fact arrives.
ALTER TABLE ext_pick_task ADD COLUMN product_code varchar(64);
ALTER TABLE ext_pick_task ADD COLUMN location_name varchar(255);
ALTER TABLE ext_pick_task ADD COLUMN location_barcode varchar(64);

COMMENT ON COLUMN ext_pick_task.product_code IS
    'Scannable EAN/UPC code of the task''s SKU (#2217). Null when the SKU carries none, its code type is not a scan scheme, or the row predates this column.';
COMMENT ON COLUMN ext_pick_task.location_name IS
    'Suggested storage location''s name, used as its human-readable code (#2217). Null when not yet replicated.';
COMMENT ON COLUMN ext_pick_task.location_barcode IS
    'Suggested storage location''s barcode, when it carries one (#2217). Null when not yet replicated or the location has none.';
