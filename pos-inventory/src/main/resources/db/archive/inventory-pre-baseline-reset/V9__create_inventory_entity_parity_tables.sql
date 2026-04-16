-- Adds missing inventory-domain entity tables for fresh Flyway bootstrap parity.

CREATE TABLE IF NOT EXISTS advance_shipping_notice (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS asn_line (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS purchase_order (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS purchase_order_line (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS inventory_allocation (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS inventory_reservation (
    id UUID PRIMARY KEY,
    stock_item_id UUID NOT NULL,
    priority INTEGER NOT NULL DEFAULT 5,
    waiting_since TIMESTAMP WITH TIME ZONE,
    due_date_time TIMESTAMP WITH TIME ZONE,
    schedule_start_time TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS inventory_pick_list (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS inventory_pick_task (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS inventory_return (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS inventory_return_line (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS approval_threshold_config (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS goods_receipt (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS goods_receipt_line (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS distributor_feed_exception (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS distributor_normalized_inventory (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS normalized_availability (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS unmapped_manufacturer_part (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS cycle_count_plan (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS cycle_count_task (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS count_entry (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS cycle_count_adjustment (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS inventory_ledger_entry (
    id UUID PRIMARY KEY
);
