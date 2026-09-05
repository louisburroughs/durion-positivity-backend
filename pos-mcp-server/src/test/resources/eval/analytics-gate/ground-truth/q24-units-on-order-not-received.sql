-- Ground truth for gate question Q24 (#1689 band 4, and the column trap):
--   "How many units are still on order but not yet received?"
--
-- SEMANTICS:
--   open_quantity_decimal is the outstanding balance on a purchase-order line. It is maintained by
--   pos-inventory on the order module's row (V14__purchase_order.sql: "Received quantities stay
--   pos-inventory's work"), so quantity_decimal - open_quantity_decimal is a CROSS-CHECK on how
--   much arrived, never the citation for it — the authoritative received quantity is
--   goods_receipt_line.quantity_received in pos_inventory_db (#1781).
--
--   The trap this question exists to catch: a model reaching for quantity_decimal answers "how
--   much did we order", which on this dataset is roughly three times the outstanding figure. Both
--   numbers are plausible; only one answers the question.

-- DB: pos_order_db
SELECT
    sum(l.quantity_decimal)                                        AS units_ordered,
    sum(l.open_quantity_decimal)                                   AS units_still_open,
    sum(l.quantity_decimal - coalesce(l.open_quantity_decimal, 0)) AS units_received_derived
FROM purchase_order_line l
JOIN purchase_order po ON po.purchase_order_id = l.purchase_order_id
WHERE po.status IN ('APPROVED', 'FULLY_RECEIVED');

SELECT
    l.sku_id,
    sum(l.open_quantity_decimal) AS units_still_open
FROM purchase_order_line l
JOIN purchase_order po ON po.purchase_order_id = l.purchase_order_id
WHERE po.status IN ('APPROVED', 'FULLY_RECEIVED')
GROUP BY l.sku_id
ORDER BY units_still_open DESC, l.sku_id
LIMIT 5;
