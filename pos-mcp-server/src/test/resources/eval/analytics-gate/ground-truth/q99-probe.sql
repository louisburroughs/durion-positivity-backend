-- Temporary schema probe (not a gate question; deleted before the PR).
-- DB: pos_inventory_db
SELECT table_name, string_agg(column_name, ', ' ORDER BY ordinal_position) AS cols
FROM information_schema.columns
WHERE table_name IN ('inventory_stock_summary','replenishment_policy')
GROUP BY table_name;

SELECT 'stock_rows' AS what, count(*)::text AS n FROM inventory_stock_summary
UNION ALL SELECT 'policy_rows', count(*)::text FROM replenishment_policy;

-- DB: pos_workorder_db
SELECT 'wo_part_rows' AS what, count(*)::text AS n FROM workorder_part
UNION ALL SELECT 'total_consumed', coalesce(sum(quantity_consumed),0)::text FROM workorder_part;
