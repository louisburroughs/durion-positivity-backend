-- Wave 2 (odoo parity #1066/#1067/#1068/#1069): monetary model, order discount, notes, quotes,
-- and the catalog/location reference replicas (ADR-0044 §6) backing pricing and tax.

-- B2 (#1067): line-level monetary fields + notes
ALTER TABLE sales_order_line ADD COLUMN discount_percent NUMERIC(5, 2);
ALTER TABLE sales_order_line ADD COLUMN discount_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE sales_order_line ADD COLUMN line_subtotal NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE sales_order_line ADD COLUMN tax_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE sales_order_line ADD COLUMN line_total NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE sales_order_line ADD COLUMN customer_note VARCHAR(1000);
ALTER TABLE sales_order_line ADD COLUMN internal_note VARCHAR(1000);
-- B1 (#1066): audit artifact for the applied pricing rules (pos-price breakdown JSON)
ALTER TABLE sales_order_line ADD COLUMN pricing_breakdown TEXT;

-- B2 (#1067): order-level totals + order discount + general note
ALTER TABLE sales_order ADD COLUMN discount_total NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE sales_order ADD COLUMN tax_total NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE sales_order ADD COLUMN grand_total NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE sales_order ADD COLUMN tax_stale BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sales_order ADD COLUMN general_note VARCHAR(2000);
ALTER TABLE sales_order ADD COLUMN order_discount_type VARCHAR(16);
ALTER TABLE sales_order ADD COLUMN order_discount_value NUMERIC(19, 4);
ALTER TABLE sales_order ADD COLUMN order_discount_reason_code VARCHAR(64);

-- A3 (#1069): counter-quote expiry
ALTER TABLE sales_order ADD COLUMN quote_expires_at TIMESTAMP;

-- B1 (#1066): product reference replica fed by catalog.events.v1 (sku -> productId, description,
-- tracking level). pos-catalog is a domain module; sync REST toward it is barred by ADR-0044.
CREATE TABLE IF NOT EXISTS ext_product (
  product_id UUID PRIMARY KEY,
  sku VARCHAR(255),
  name VARCHAR(500),
  active BOOLEAN NOT NULL,
  tracking_level VARCHAR(32),
  aggregate_version BIGINT NOT NULL,
  synced_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ext_product_sku ON ext_product (sku);

-- B3 (#1068): location reference replica fed by location.events.v1 (tax jurisdiction address,
-- friendly location code). Mirrors pos-workorder's ext_location pattern (ADR-0044 §6, #892).
CREATE TABLE IF NOT EXISTS ext_location (
  location_id UUID PRIMARY KEY,
  code VARCHAR(64),
  name VARCHAR(255),
  active BOOLEAN NOT NULL,
  address_line1 VARCHAR(255),
  address_line2 VARCHAR(255),
  city VARCHAR(128),
  region VARCHAR(64),
  postal_code VARCHAR(32),
  country VARCHAR(8),
  aggregate_version BIGINT NOT NULL,
  synced_at TIMESTAMP NOT NULL
);
