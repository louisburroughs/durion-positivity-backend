-- Issue #1024 (odoo-parity A1): stored on-hand snapshot.
-- Derived balance read model: one row per (stock_item_id, location_id), maintained
-- transactionally with every ledger append through LedgerPostingService. The ledger
-- remains the source of truth; StockSummaryRebuildService reconstructs this table from
-- scratch and StockSummaryDriftVerifier compares it against SUM(ledger) (report-only).
--
-- Forward-compatibility (do not reshape later):
--   * WS-E per-lot rows: add a nullable lot_id column and extend the unique index to
--     (stock_item_id, location_id, lot_id) NULLS NOT DISTINCT.
--   * WS-C in-transit: add an in_transit_qty column.
CREATE TABLE inventory_stock_summary (
    summary_id uuid NOT NULL,
    stock_item_id varchar(255) NOT NULL,
    -- Nullable: ledger entries may be posted without a location (e.g. workorder
    -- consumption, cycle-count variances); their balances aggregate on a NULL-location row.
    location_id uuid,
    on_hand bigint NOT NULL DEFAULT 0,
    allocated bigint NOT NULL DEFAULT 0,
    -- Reservation events (RESERVATION_CREATED/RELEASED) are tracked separately from hard
    -- allocations because the availability endpoints expose different ATP semantics
    -- (ADR-0001: allocations only) vs the per-location availability list (reservations too).
    reserved bigint NOT NULL DEFAULT 0,
    -- atp = on_hand - allocated (ADR-0001 definition; maintained by LedgerPostingService).
    atp bigint NOT NULL DEFAULT 0,
    last_ledger_entry_id uuid,
    last_event_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT inventory_stock_summary_pkey PRIMARY KEY (summary_id)
);

-- NULLS NOT DISTINCT so at most one NULL-location row can exist per stock item.
CREATE UNIQUE INDEX uq_inventory_stock_summary_key
    ON inventory_stock_summary (stock_item_id, location_id) NULLS NOT DISTINCT;

CREATE INDEX idx_inventory_stock_summary_location ON inventory_stock_summary (location_id);
