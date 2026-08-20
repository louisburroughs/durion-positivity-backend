-- ADR-0055 stage 2 (#1414): widen the inventory quantity chain to numeric(19,4).
--
-- Divisibility is a per-product property declared by the catalog
-- (product_uom.precision_scale, replicated here as ext_product_uom). Widening these columns
-- does NOT make stock divisible: a product declaring scale 0 — which is every product until
-- one is seeded otherwise — is still refused a fractional quantity on both the demand side
-- (pos-workorder's part gate, #1413) and the supply side (receiving, ASN, returns). The width
-- exists so that a product declaring scale > 0 has somewhere truthful to land.
--
-- numeric(19,4) matches workorder_part.quantity, which has always been decimal. The scale of 4
-- is the ceiling the platform will store; the per-product declaration is what decides how much
-- of it any given product may use.
--
-- Pre-production widening: numeric(19,4) is a strict superset of integer and bigint for every
-- value those columns can hold, so the implicit cast preserves existing rows exactly. There is
-- no data-preservation logic here because there is nothing to preserve beyond the cast.

-- 1. The ledger. change_in_quantity and quantity_after are the two columns #1365's Option A
--    description omitted, and they are the reason the widening cannot stop at the read model.
ALTER TABLE inventory_ledger_entry
    ALTER COLUMN change_in_quantity TYPE numeric(19,4),
    ALTER COLUMN quantity_after TYPE numeric(19,4);

-- 2. The reservation chain.
ALTER TABLE inventory_reservation
    ALTER COLUMN required_quantity TYPE numeric(19,4),
    ALTER COLUMN allocated_quantity TYPE numeric(19,4);

ALTER TABLE inventory_allocation
    ALTER COLUMN allocated_quantity TYPE numeric(19,4);

-- backorder_record.quantity_short keeps its positivity CHECK. The constraint is dropped and
-- re-added rather than left alone because "> 0" on numeric is a different comparison than on
-- integer: 0.0000 must be rejected the same way 0 was, and a fractional shortfall of 0.5 must
-- be accepted. Re-stating it makes that explicit rather than relying on the implicit re-check.
ALTER TABLE backorder_record DROP CONSTRAINT backorder_record_quantity_check;
ALTER TABLE backorder_record
    ALTER COLUMN quantity_short TYPE numeric(19,4);
ALTER TABLE backorder_record
    ADD CONSTRAINT backorder_record_quantity_check CHECK (quantity_short > 0);

-- 3. The derived read model. Already 64-bit (bigint), so this is a scale change rather than a
--    range change: the ledger it derives from can now carry decimals, and a summary that could
--    not would silently truncate every rebuild.
ALTER TABLE inventory_stock_summary
    ALTER COLUMN on_hand TYPE numeric(19,4),
    ALTER COLUMN allocated TYPE numeric(19,4),
    ALTER COLUMN reserved TYPE numeric(19,4),
    ALTER COLUMN atp TYPE numeric(19,4),
    ALTER COLUMN in_transit_qty TYPE numeric(19,4);

-- 4. Returns. The return document's own quantities post straight into the ledger, so leaving
--    them integral would reinstate the whole-units rule one layer up from the column that just
--    lost it — a divisible product could be received and issued fractionally but not returned.
ALTER TABLE inventory_return
    ALTER COLUMN total_items_returned TYPE numeric(19,4);

ALTER TABLE inventory_return_line
    ALTER COLUMN quantity_returned TYPE numeric(19,4);

-- 5. Cycle-count adjustments. The variance posts straight into the ledger, so an integral
--    variance column would have rounded away exactly the small fractional shrinkage a bulk SKU
--    produces — the one thing the platform gets to notice about a drum that is quietly draining.
--    The count-capture DTOs stay integral; letting a counter key a decimal, and the tolerance
--    policy that decides which small variances are worth surfacing, are stage 4 (#1416).
ALTER TABLE cycle_count_adjustment
    ALTER COLUMN counted_quantity TYPE numeric(19,4),
    ALTER COLUMN quantity_change TYPE numeric(19,4),
    ALTER COLUMN quantity_on_hand_before TYPE numeric(19,4);

-- 6. Costing state and revaluation. The costing engine multiplies a movement quantity into a
--    unit cost, so an integral running quantity would have made the AVCO denominator disagree
--    with the ledger it is derived from the moment a fractional receipt posted.
ALTER TABLE sku_cost_state
    ALTER COLUMN on_hand_qty TYPE numeric(19,4);

ALTER TABLE revaluation_record
    ALTER COLUMN on_hand_quantity TYPE numeric(19,4);

-- 7. Shortage resolution. A shortage is measured against the same ledger and resolves into a
--    backorder or a reservation, both of which are now decimal; an integral short quantity here
--    would have been the one remaining place that rounded the shortfall before recording it.
ALTER TABLE shortage_resolution_record DROP CONSTRAINT shortage_resolution_short_quantity_check;
ALTER TABLE shortage_resolution_record
    ALTER COLUMN short_quantity TYPE numeric(19,4);
ALTER TABLE shortage_resolution_record
    ADD CONSTRAINT shortage_resolution_short_quantity_check CHECK (short_quantity > 0);

-- 8. Manual adjustments. An adjustment request posts to the ledger on approval, so it carries
--    the same divisibility rule as every other posting path.
ALTER TABLE inventory_adjustment_request
    ALTER COLUMN quantity TYPE numeric(19,4);
