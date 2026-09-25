-- #2203: goods receipts must stamp the document unit cost on their GOODS_RECEIPT ledger rows, so a
-- received SKU gains a cost under AVERAGE (ADR-0048 IMP-002). A receiving session takes that cost
-- from the purchase order line it receives against, which needs two things this schema lacked:
--
-- 1. which purchase order line a receiving line came from. Sessions have always been built one
--    line per open order line, but the link was never kept.
-- 2. what unit the projected unit_cost_minor prices. pos-order keeps it per document unit when the
--    line was keyed in one (a case, a pack), so the replica needs the base-per-document-unit factor
--    to reach a per-base-unit cost.
--
-- Both nullable rather than backfilled. A receiving line opened before this column existed has no
-- link (receiving falls back to the order's only line for the SKU, if there is exactly one), and an
-- order line projected before pos-order published the factor has none until the order's next fact
-- replaces the row; receiving then posts the line without a document cost, exactly as before.
ALTER TABLE receiving_line ADD COLUMN source_line_id uuid;

COMMENT ON COLUMN receiving_line.source_line_id IS
    'Purchase order line this receiving line was built from (#2203). Null for lines built before the column existed.';

ALTER TABLE ext_purchase_order_line ADD COLUMN conversion_factor numeric(20, 6);

COMMENT ON COLUMN ext_purchase_order_line.conversion_factor IS
    'Base units per unit that unit_cost_minor prices: the document-UoM factor, or 1 for a line keyed in base (#2203). Null when the order has not been projected since pos-order began publishing it.';
