ALTER TABLE IF EXISTS inventory_reservation
    ADD COLUMN IF NOT EXISTS stock_item_id UUID;

UPDATE inventory_reservation
SET stock_item_id = CAST(NULLIF(sku, '') AS UUID)
WHERE stock_item_id IS NULL
  AND sku IS NOT NULL;

ALTER TABLE IF EXISTS inventory_reservation
    ALTER COLUMN stock_item_id SET NOT NULL;

ALTER TABLE IF EXISTS inventory_reservation
    DROP COLUMN IF EXISTS sku;

ALTER TABLE IF EXISTS inventory_allocation_audit
    ALTER COLUMN stock_item_id TYPE UUID
    USING CAST(NULLIF(stock_item_id, '') AS UUID);
