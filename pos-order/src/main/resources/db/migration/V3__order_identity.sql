-- Story A2 (odoo parity #1060): human-facing order number, location, draft parking label.
-- Pre-existing rows keep NULL order_number/location_id; numbers are assigned to new carts only.
ALTER TABLE sales_order ADD COLUMN order_number VARCHAR(64);
ALTER TABLE sales_order ADD COLUMN location_id UUID;
ALTER TABLE sales_order ADD COLUMN label VARCHAR(255);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sales_order_order_number ON sales_order (order_number);
CREATE INDEX IF NOT EXISTS idx_sales_order_clerk_status ON sales_order (clerk_id, status);
CREATE INDEX IF NOT EXISTS idx_sales_order_terminal_status ON sales_order (terminal_id, status);

CREATE TABLE IF NOT EXISTS order_number_sequence (
  location_id UUID NOT NULL,
  period VARCHAR(4) NOT NULL,
  next_value BIGINT NOT NULL,
  PRIMARY KEY (location_id, period)
);
