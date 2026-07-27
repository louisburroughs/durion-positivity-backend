-- odoo-parity E4 (#1050, spec §6 E4): serial-number tracking for SERIAL-tracked stock items.
--
-- inventory_serial_unit: one row per physical serialized unit, unique per (stock_item_id,
-- serial_number). Created lazily by the ledger posting funnel when a SERIAL-tracked product is
-- received (serial enumeration = quantity invariant: a receipt of N units enumerates N serials),
-- and transitioned by the funnel as matching outbound/inbound entries name each serial.
--
-- Only IN_STOCK units count toward serial-derived on-hand; the serial-vs-ledger verifier
-- reconciles this against the lot-agnostic inventory_stock_summary balance (report-only).
--
-- workorder_id is the consumption linkage that finally joins warranty's
-- warranty_part_return_hold.serial_number to a stock record.
CREATE TABLE inventory_serial_unit (
    serial_unit_id uuid NOT NULL,
    stock_item_id varchar(255) NOT NULL,
    serial_number varchar(128) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'IN_STOCK',
    location_id uuid,
    lot_id uuid,
    receipt_ledger_entry_id uuid,
    consumption_ledger_entry_id uuid,
    workorder_id varchar(255),
    received_at timestamp(6) with time zone,
    consumed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT inventory_serial_unit_pkey PRIMARY KEY (serial_unit_id),
    CONSTRAINT ck_inventory_serial_unit_status
        CHECK (status IN ('IN_STOCK', 'ISSUED', 'RETURNED', 'SCRAPPED'))
);

CREATE UNIQUE INDEX uq_inventory_serial_unit_sku_serial
    ON inventory_serial_unit (stock_item_id, serial_number);
CREATE INDEX idx_inventory_serial_unit_stock_item ON inventory_serial_unit (stock_item_id);
CREATE INDEX idx_inventory_serial_unit_on_hand
    ON inventory_serial_unit (stock_item_id, location_id, status);
CREATE INDEX idx_inventory_serial_unit_workorder
    ON inventory_serial_unit (workorder_id)
    WHERE workorder_id IS NOT NULL;
