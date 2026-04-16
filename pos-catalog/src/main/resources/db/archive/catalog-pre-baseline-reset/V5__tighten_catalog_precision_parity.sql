-- Phase 5.3 precision parity tightening for catalog.

ALTER TABLE IF EXISTS product
    ALTER COLUMN sku SET NOT NULL,
    ALTER COLUMN name SET NOT NULL,
    ALTER COLUMN active SET DEFAULT TRUE,
    ALTER COLUMN active SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE IF EXISTS service
    ALTER COLUMN code SET NOT NULL,
    ALTER COLUMN name SET NOT NULL,
    ALTER COLUMN active SET DEFAULT TRUE,
    ALTER COLUMN active SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE IF EXISTS supplier_item_cost
    ALTER COLUMN supplier_id SET NOT NULL,
    ALTER COLUMN item_id SET NOT NULL,
    ALTER COLUMN base_cost SET NOT NULL,
    ALTER COLUMN currency_code TYPE VARCHAR(3),
    ALTER COLUMN currency_code SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE IF EXISTS item_cost
    ALTER COLUMN item_id SET NOT NULL,
    ALTER COLUMN standard_cost SET DEFAULT 0,
    ALTER COLUMN standard_cost SET NOT NULL,
    ALTER COLUMN average_cost SET DEFAULT 0,
    ALTER COLUMN average_cost SET NOT NULL,
    ALTER COLUMN last_cost SET DEFAULT 0,
    ALTER COLUMN last_cost SET NOT NULL,
    ALTER COLUMN qty_on_hand SET DEFAULT 0,
    ALTER COLUMN qty_on_hand SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_product_sku ON product (sku);
CREATE INDEX IF NOT EXISTS idx_supplier_item_cost_supplier_item
    ON supplier_item_cost (supplier_id, item_id);
CREATE INDEX IF NOT EXISTS idx_item_cost_item ON item_cost (item_id);
