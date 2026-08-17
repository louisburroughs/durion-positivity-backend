-- CAP #1315: owned-ATP gate for order/workorder line creation. Reservations and backorders were
-- hard-coded to a workorder line as the only possible demand source. A sales order needs the same
-- machinery (soft reservation, hard promotion, backorder-on-shortfall, auto-resolution as stock
-- arrives) for its checkout gate, so both tables gain an alternative demand key rather than being
-- redesigned: workorder_line_id becomes optional, sales_order_line_id is the new alternative, and
-- exactly one of the two must be set. Existing rows are all workorder-line rows and remain valid
-- under the new CHECK constraint unchanged.

-- ── inventory_reservation ───────────────────────────────────────────────────────────────────
ALTER TABLE inventory_reservation
    DROP CONSTRAINT inventory_reservation_workorder_line_id_key;
ALTER TABLE inventory_reservation
    ALTER COLUMN workorder_line_id DROP NOT NULL;
ALTER TABLE inventory_reservation
    ADD COLUMN sales_order_line_id uuid;
ALTER TABLE inventory_reservation
    ADD CONSTRAINT ck_inventory_reservation_one_demand_line
        CHECK ((workorder_line_id IS NOT NULL) <> (sales_order_line_id IS NOT NULL));

CREATE UNIQUE INDEX ux_inventory_reservation_workorder_line
    ON inventory_reservation (workorder_line_id) WHERE workorder_line_id IS NOT NULL;
CREATE UNIQUE INDEX ux_inventory_reservation_sales_order_line
    ON inventory_reservation (sales_order_line_id) WHERE sales_order_line_id IS NOT NULL;

-- ── backorder_record ─────────────────────────────────────────────────────────────────────────
ALTER TABLE backorder_record
    ALTER COLUMN workorder_line_id DROP NOT NULL;
ALTER TABLE backorder_record
    ADD COLUMN sales_order_line_id uuid;
ALTER TABLE backorder_record
    ADD CONSTRAINT ck_backorder_record_one_demand_line
        CHECK ((workorder_line_id IS NOT NULL) <> (sales_order_line_id IS NOT NULL));

-- Duplicate-open guard for the new demand key, mirroring V24's ux_backorder_record_open_line_sku.
CREATE UNIQUE INDEX ux_backorder_record_open_sales_order_line_sku
    ON backorder_record (sales_order_line_id, sku) WHERE status = 'OPEN' AND sales_order_line_id IS NOT NULL;

CREATE INDEX idx_backorder_record_sales_order_line ON backorder_record (sales_order_line_id);
