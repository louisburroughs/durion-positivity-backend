-- Temporary probe (deleted before merge): can parts usage be derived from purchasing?
-- DB: pos_order_db
SELECT string_agg(column_name, ', ' ORDER BY ordinal_position) AS purchase_order_line_cols
FROM information_schema.columns WHERE table_name = 'purchase_order_line';

SELECT count(*) AS po_lines FROM purchase_order_line;

-- DB: pos_inventory_db
SELECT string_agg(column_name, ', ' ORDER BY ordinal_position) AS policy_cols
FROM information_schema.columns WHERE table_name = 'replenishment_policy';

SELECT itemsku, location_id, minimum_quantity, maximum_quantity, active FROM replenishment_policy;
