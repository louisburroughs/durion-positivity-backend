-- odoo-parity E2 (#1042, spec §6 E2): lot-aware outbound flows.
--
-- The ledger (lot_id, V18) and the dual-row summary bookkeeping already support lots — this
-- migration only adds the document-side columns the outbound flows persist.

-- 1) Pick tasks: advisory FIFO lot suggestion stamped at generation (earliest lot received_at
--    among ACTIVE lots with positive per-lot on-hand at the suggested location; E3 upgrades
--    the ordering to FEFO via the LotExpiryProvider SPI), plus the lot actually keyed at pick
--    confirmation — the confirm may override the suggestion with any valid ACTIVE lot, and
--    picked_lot_id is what the consumption posting carries.
ALTER TABLE inventory_pick_task ADD COLUMN suggested_lot_number varchar(128);
ALTER TABLE inventory_pick_task ADD COLUMN picked_lot_id uuid;

-- 2) Transfer-order lines: the lot resolved at dispatch is pinned on the line so receive and
--    short-close post the SAME lot on both sides — per-lot balances conserve across sites
--    exactly like the lot-agnostic ones.
ALTER TABLE transfer_order_line ADD COLUMN lot_id uuid;
ALTER TABLE transfer_order_line ADD COLUMN lot_number varchar(128);

-- scrap_record.lot_id already exists (V12, reserved by D1) and is now populated for
-- LOT-tracked scraps. Returns and consumption stamp the ledger rows only — the ledger plus
-- sourceTransactionId is the traceability source (E5).
