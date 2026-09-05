-- Temporary probe (deleted before merge).
-- DB: pos_order_db
SELECT sku_id, sum(quantity_decimal) AS ordered, sum(open_quantity_decimal) AS still_open,
       sum(quantity_decimal - COALESCE(open_quantity_decimal,0)) AS received, count(*) AS lines
FROM purchase_order_line GROUP BY sku_id ORDER BY ordered DESC LIMIT 8;

-- DB: pos_inventory_db
SELECT s.stock_item_id, s.location_id, s.on_hand, s.atp, p.itemsku, p.minimum_quantity,
       (s.atp < p.minimum_quantity) AS is_running_low
FROM inventory_stock_summary s
JOIN replenishment_policy p ON p.location_id = s.location_id AND p.active
LIMIT 10;
