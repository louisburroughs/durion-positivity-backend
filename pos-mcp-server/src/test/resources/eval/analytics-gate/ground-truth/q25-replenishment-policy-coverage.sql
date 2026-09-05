-- Ground truth for gate question Q25 (#1689, stock levels asked so the answer is gradeable):
--   "How many of our stocked product-locations have an active replenishment policy, and which
--    SKU is it?"
--
-- WHY THIS SHAPE:
--   "What is running low" is the natural question and is not gradeable here: exactly one active
--   policy exists, so the answer set is one element or empty and a model that names the only
--   policied SKU scores without checking a quantity (#1781). Asked the other way round, the
--   coverage gap becomes the graded fact — the count and the SKU are both exact, and neither is
--   guessable from the question.
--
--   "Running low" itself is ATP-at-now below the policy minimum, per the ratified glossary
--   (#1781, BusinessGlossary 2026-09-05.2) — deliberately not the replenishment engine's
--   projected-available-at-lead-horizon.

-- DB: pos_inventory_db
SELECT count(*) AS stocked_product_locations FROM inventory_stock_summary;

SELECT
    p.itemsku,
    p.location_id,
    p.minimum_quantity,
    p.maximum_quantity,
    p.active
FROM replenishment_policy p
WHERE p.active
ORDER BY p.itemsku;

-- Stock rows for the policied SKU, if any: the policy's location carries no stock summary, which
-- is a legitimate policy-before-stock state and the reason a "running low" answer is currently
-- empty rather than one row.
SELECT s.location_id, s.on_hand, s.atp
FROM inventory_stock_summary s
WHERE s.stock_item_id IN (SELECT itemsku FROM replenishment_policy WHERE active);
