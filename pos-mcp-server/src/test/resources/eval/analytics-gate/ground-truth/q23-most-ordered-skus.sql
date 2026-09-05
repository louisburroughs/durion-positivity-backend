-- Ground truth for gate question Q23 (#1689 band 5, top-N by derived metric):
--   "Across purchase orders that were approved or received, which three SKUs did we order the
--    most units of, and how many units of each?"
--
-- SEMANTICS:
--   "Ordered" is the quantity on a purchase-order line, summed per SKU across lines. The status
--   filter is the graded part: DRAFT was never sent to a vendor and CANCELLED is void, so neither
--   is an order we placed. On the current dataset that is 90 of 402 orders — an answer that
--   ignores status is wrong by a fifth of the population while looking entirely plausible.
--
--   Ordered is NOT usage. The inventory domain ruling (#1781) is that replenishment orders to the
--   policy maximum and rounds up to orderMultiple, so ranking SKUs by ordered quantity ranks max
--   levels and vendor packaging, not demand. This question asks what we ORDERED and must not be
--   read as what we use.

-- DB: pos_order_db
SELECT
    l.sku_id,
    sum(l.quantity_decimal)                       AS units_ordered,
    count(*)                                      AS lines,
    count(DISTINCT l.purchase_order_id)           AS purchase_orders
FROM purchase_order_line l
JOIN purchase_order po ON po.purchase_order_id = l.purchase_order_id
WHERE po.status IN ('APPROVED', 'FULLY_RECEIVED')
GROUP BY l.sku_id
ORDER BY units_ordered DESC, l.sku_id
LIMIT 5;

-- The excluded population, so a status-blind answer is visibly distinguishable.
SELECT
    po.status,
    count(DISTINCT po.purchase_order_id) AS orders,
    coalesce(sum(l.quantity_decimal), 0) AS units
FROM purchase_order po
LEFT JOIN purchase_order_line l ON l.purchase_order_id = po.purchase_order_id
GROUP BY po.status
ORDER BY orders DESC;
