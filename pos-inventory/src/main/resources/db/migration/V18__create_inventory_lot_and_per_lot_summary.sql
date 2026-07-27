-- odoo-parity E1 (#1038, spec §6 E1): lot master + inbound capture + per-lot summary rows.
--
-- 1) inventory_lot: one row per (stock_item_id, lot_number), created lazily by the inbound
--    receipt paths for LOT-tracked products (tracking level from the ext_product replica).
--    expiration_date stays NULL until the E3 expiry flows populate it.
CREATE TABLE inventory_lot (
    lot_id uuid NOT NULL,
    stock_item_id varchar(255) NOT NULL,
    lot_number varchar(128) NOT NULL,
    received_at timestamp(6) with time zone NOT NULL,
    vendor_id uuid,
    expiration_date date,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT inventory_lot_pkey PRIMARY KEY (lot_id),
    CONSTRAINT ck_inventory_lot_status
        CHECK (status IN ('ACTIVE', 'QUARANTINED', 'RECALLED', 'CONSUMED'))
);

CREATE UNIQUE INDEX uq_inventory_lot_sku_lot_number ON inventory_lot (stock_item_id, lot_number);
CREATE INDEX idx_inventory_lot_stock_item ON inventory_lot (stock_item_id);

-- 2) Nullable lot linkage on the ledger. Only receipt postings populate it in E1; outbound
--    lot-awareness (picks/consumption/transfers/scraps) is the E2 story. Untracked products
--    keep NULL everywhere — zero behavior change.
ALTER TABLE inventory_ledger_entry ADD COLUMN lot_id uuid;
CREATE INDEX idx_inventory_ledger_entry_lot
    ON inventory_ledger_entry (lot_id)
    WHERE lot_id IS NOT NULL;

-- 3) Per-lot summary rows (the A1 forward-compatibility note executed): lot_id joins the
--    summary key with NULLS NOT DISTINCT so the lot-agnostic (NULL lot_id) row per
--    (stock_item_id, location_id) stays unique next to the per-lot rows.
--
--    Dual-row bookkeeping rule (maintained by LedgerPostingService, replayed by rebuild and
--    drift verification): EVERY ledger entry applies its deltas to the lot-agnostic row —
--    which therefore keeps serving all availability/rollup/forecast readers byte-identically
--    to pre-E1 — and a lot-tagged entry ADDITIONALLY applies the same deltas to its
--    (stock_item_id, location_id, lot_id) row, from which the lot read API serves per-lot
--    on-hand.
ALTER TABLE inventory_stock_summary ADD COLUMN lot_id uuid;

DROP INDEX uq_inventory_stock_summary_key;
CREATE UNIQUE INDEX uq_inventory_stock_summary_key
    ON inventory_stock_summary (stock_item_id, location_id, lot_id) NULLS NOT DISTINCT;

CREATE INDEX idx_inventory_stock_summary_lot
    ON inventory_stock_summary (lot_id)
    WHERE lot_id IS NOT NULL;

-- 4) Receiving lines now persist the keyed lot number (goods-receipt and ASN lines already
--    carry one since baseline).
ALTER TABLE receiving_line ADD COLUMN lot_number varchar(128);
