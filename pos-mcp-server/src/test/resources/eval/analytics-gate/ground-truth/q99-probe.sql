-- Temporary probe (deleted before merge).
-- DB: pos_workorder_db
SELECT event_type, count(*) AS events, sum(quantity) AS qty
FROM workorder_part_usage_event GROUP BY event_type ORDER BY events DESC;

SELECT sum(quantity_issued) AS issued, sum(quantity_consumed) AS consumed,
       sum(quantity_returned) AS returned FROM workorder_part;

-- DB: pos_inventory_db
SELECT location_id, lot_id, on_hand, allocated, reserved, atp
FROM inventory_stock_summary WHERE stock_item_id = 'OIL-5W30-5QT';

SELECT count(*) AS rows_where_atp_differs_from_onhand
FROM inventory_stock_summary WHERE atp IS DISTINCT FROM on_hand;
