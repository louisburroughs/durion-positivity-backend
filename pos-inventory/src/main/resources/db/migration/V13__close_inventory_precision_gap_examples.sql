-- Phase 5.3 follow-up: close remaining inventory precision-gap examples.

ALTER TABLE IF EXISTS inventory_return_line
    ADD COLUMN IF NOT EXISTS line_id UUID,
    ADD COLUMN IF NOT EXISTS sku_id UUID,
    ADD COLUMN IF NOT EXISTS quantity_returned NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

ALTER TABLE IF EXISTS inventory_pick_list
    ADD COLUMN IF NOT EXISTS status VARCHAR(64),
    ADD COLUMN IF NOT EXISTS priority INTEGER;

ALTER TABLE IF EXISTS inventory_return_line
    ALTER COLUMN return_id SET NOT NULL,
    ALTER COLUMN quantity_returned SET DEFAULT 0,
    ALTER COLUMN quantity_returned SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE IF EXISTS asn_line
    ALTER COLUMN asn_id SET NOT NULL,
    ALTER COLUMN po_id SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL;
