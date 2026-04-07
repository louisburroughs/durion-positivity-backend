-- Phase 5.3 precision parity tightening for inventory.

ALTER TABLE IF EXISTS purchase_order_line
    ALTER COLUMN purchase_order_id SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE IF EXISTS goods_receipt_line
    ALTER COLUMN receipt_id SET NOT NULL,
    ALTER COLUMN po_line_id SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE IF EXISTS inventory_pick_list
    ALTER COLUMN pick_list_id SET NOT NULL,
    ALTER COLUMN workorder_id SET NOT NULL,
    ALTER COLUMN due_at SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE IF EXISTS inventory_pick_task
    ALTER COLUMN pick_task_id SET NOT NULL,
    ALTER COLUMN pick_list_id SET NOT NULL,
    ALTER COLUMN product_id SET NOT NULL,
    ALTER COLUMN quantity_required SET DEFAULT 0,
    ALTER COLUMN quantity_required SET NOT NULL,
    ALTER COLUMN quantity_picked SET DEFAULT 0,
    ALTER COLUMN quantity_picked SET NOT NULL,
    ALTER COLUMN sort_order SET DEFAULT 0,
    ALTER COLUMN sort_order SET NOT NULL,
    ALTER COLUMN due_at SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE IF EXISTS cycle_count_task
    ALTER COLUMN task_id SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN count_entries_count SET DEFAULT 0,
    ALTER COLUMN count_entries_count SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE IF EXISTS count_entry
    ALTER COLUMN count_entry_id SET NOT NULL,
    ALTER COLUMN cycle_count_task_id SET NOT NULL,
    ALTER COLUMN expected_quantity SET DEFAULT 0,
    ALTER COLUMN expected_quantity SET NOT NULL,
    ALTER COLUMN actual_quantity SET DEFAULT 0,
    ALTER COLUMN actual_quantity SET NOT NULL,
    ALTER COLUMN variance SET DEFAULT 0,
    ALTER COLUMN variance SET NOT NULL,
    ALTER COLUMN counted_at SET DEFAULT NOW(),
    ALTER COLUMN counted_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_purchase_order_line_purchase_order_id
    ON purchase_order_line (purchase_order_id);
CREATE INDEX IF NOT EXISTS idx_goods_receipt_line_receipt_id
    ON goods_receipt_line (receipt_id);
CREATE INDEX IF NOT EXISTS idx_inventory_pick_task_pick_list_id
    ON inventory_pick_task (pick_list_id);
CREATE INDEX IF NOT EXISTS idx_count_entry_cycle_count_task_id
    ON count_entry (cycle_count_task_id);
