-- Stories I1/I2 (odoo parity #1064/#1065): idempotent intake + typed, validated customer refs.

-- I1: cart-creation idempotency key and client line identity (Odoo uuid-rewrite analog).
ALTER TABLE sales_order ADD COLUMN creation_idempotency_key VARCHAR(128);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sales_order_creation_idem_key ON sales_order (creation_idempotency_key);
ALTER TABLE sales_order_line ADD COLUMN client_line_uuid UUID;
CREATE UNIQUE INDEX IF NOT EXISTS uk_sales_order_line_client_uuid ON sales_order_line (order_id, client_line_uuid);

-- I2: customer/vehicle become typed UUID references validated against pos-customer.
-- Pre-production recast: legacy free-text values are dropped, not migrated (recorded in plan
-- ground rules; no production data exists for pos-order).
ALTER TABLE sales_order ADD COLUMN customer_uuid UUID;
ALTER TABLE sales_order ADD COLUMN vehicle_uuid UUID;
ALTER TABLE sales_order DROP COLUMN customer_id;
ALTER TABLE sales_order DROP COLUMN vehicle_id;
ALTER TABLE sales_order RENAME COLUMN customer_uuid TO customer_id;
ALTER TABLE sales_order RENAME COLUMN vehicle_uuid TO vehicle_id;
ALTER TABLE sales_order ADD COLUMN customer_validation_status VARCHAR(32);
