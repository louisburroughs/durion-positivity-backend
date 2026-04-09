CREATE TABLE IF NOT EXISTS sales_order (
  order_id UUID PRIMARY KEY,
  customer_id VARCHAR(255),
  vehicle_id VARCHAR(255),
  clerk_id VARCHAR(255) NOT NULL,
  terminal_id VARCHAR(255) NOT NULL,
  status VARCHAR(255) NOT NULL,
  subtotal NUMERIC(19, 4) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  created_by VARCHAR(255) NOT NULL,
  updated_by VARCHAR(255) NOT NULL,
  cancellation_reason VARCHAR(255),
  work_order_id UUID,
  payment_id UUID,
  cancellation_idempotency_key VARCHAR(255)
);
CREATE TABLE IF NOT EXISTS sales_order_line (
  order_line_id UUID PRIMARY KEY,
  order_id UUID NOT NULL,
  item_sku VARCHAR(255) NOT NULL,
  item_description VARCHAR(255) NOT NULL,
  quantity INTEGER NOT NULL,
  unit_price NUMERIC(19, 4) NOT NULL,
  fulfillment_status VARCHAR(255) NOT NULL,
  price_source VARCHAR(255) NOT NULL,
  reason_code VARCHAR(255),
  source_type VARCHAR(255),
  source_id VARCHAR(255),
  source_line_id VARCHAR(255),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_sales_order_line_order FOREIGN KEY (order_id) REFERENCES sales_order(order_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_sales_order_line_order_id ON sales_order_line (order_id);
CREATE TABLE IF NOT EXISTS price_override (
  override_id UUID PRIMARY KEY,
  order_id UUID NOT NULL,
  order_line_id UUID NOT NULL,
  product_id UUID NOT NULL,
  original_price NUMERIC(19, 4) NOT NULL,
  override_price NUMERIC(19, 4) NOT NULL,
  reason_code VARCHAR(255) NOT NULL,
  justification VARCHAR(2000),
  status VARCHAR(255) NOT NULL,
  requested_by_user_id UUID NOT NULL,
  approved_by_user_id UUID,
  rejected_by_user_id UUID,
  rejection_reason VARCHAR(1000),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  created_by VARCHAR(255) NOT NULL,
  updated_by VARCHAR(255) NOT NULL,
  approved_at TIMESTAMP,
  rejected_at TIMESTAMP,
  applied_at TIMESTAMP,
  requires_approval BOOLEAN NOT NULL,
  affects_commission BOOLEAN NOT NULL DEFAULT FALSE,
  idempotency_key VARCHAR(128),
  CONSTRAINT fk_price_override_order FOREIGN KEY (order_id) REFERENCES sales_order(order_id) ON DELETE CASCADE,
  CONSTRAINT fk_price_override_order_line FOREIGN KEY (order_line_id) REFERENCES sales_order_line(order_line_id) ON DELETE CASCADE,
  CONSTRAINT uk_price_override_idempotency_key UNIQUE (idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_price_override_order_id ON price_override (order_id);
CREATE INDEX IF NOT EXISTS idx_price_override_order_line_id ON price_override (order_line_id);
CREATE TABLE IF NOT EXISTS approval_record (
  record_id UUID PRIMARY KEY,
  price_override_id UUID NOT NULL,
  reviewer_user_id UUID NOT NULL,
  reviewer_role VARCHAR(255) NOT NULL,
  action VARCHAR(255) NOT NULL,
  comments VARCHAR(2000),
  action_timestamp TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  reviewer_ip_address VARCHAR(255),
  CONSTRAINT fk_approval_record_price_override FOREIGN KEY (price_override_id) REFERENCES price_override(override_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_approval_record_price_override_id ON approval_record (price_override_id);