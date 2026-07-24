-- Wave 6 (odoo parity #1086/#1087): returns & refunds.
-- Odoo refund() analog: a negative document linked line-by-line to one completed source order,
-- each line capped at the un-refunded remainder of the sold line.

CREATE TABLE return_order (
  return_order_id UUID PRIMARY KEY,
  version BIGINT NOT NULL,
  original_order_id UUID NOT NULL,
  original_order_number VARCHAR(255),
  location_id UUID,
  customer_id UUID,
  original_invoice_id UUID,
  refund_method VARCHAR(24) NOT NULL,
  status VARCHAR(24) NOT NULL,
  reason_code VARCHAR(64),
  total_refund NUMERIC(19, 4) NOT NULL,
  creation_idempotency_key VARCHAR(128) UNIQUE,
  approved_by_user_id UUID,
  approved_at TIMESTAMPTZ,
  rejection_reason VARCHAR(2000),
  failure_reason VARCHAR(2000),
  returned_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  created_by VARCHAR(255) NOT NULL,
  updated_by VARCHAR(255) NOT NULL
);

CREATE INDEX ix_return_order_original ON return_order (original_order_id);
CREATE INDEX ix_return_order_status ON return_order (status);

CREATE TABLE return_order_line (
  return_line_id UUID PRIMARY KEY,
  return_order_id UUID NOT NULL,
  original_order_line_id UUID NOT NULL,
  item_sku VARCHAR(255) NOT NULL,
  return_qty INTEGER NOT NULL,
  condition_code VARCHAR(16) NOT NULL,
  line_refund NUMERIC(19, 4) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_return_line_order FOREIGN KEY (return_order_id)
    REFERENCES return_order (return_order_id) ON DELETE CASCADE,
  CONSTRAINT chk_return_qty_positive CHECK (return_qty > 0)
);

-- The cap query sums returnQty per original line across non-cancelled returns.
CREATE INDEX ix_return_line_original ON return_order_line (original_order_line_id);
CREATE INDEX ix_return_line_order ON return_order_line (return_order_id);

CREATE TABLE return_order_line_serial (
  return_line_id UUID NOT NULL,
  serial_index INTEGER NOT NULL,
  serial_number VARCHAR(128) NOT NULL,
  PRIMARY KEY (return_line_id, serial_index),
  CONSTRAINT fk_return_line_serial_line FOREIGN KEY (return_line_id)
    REFERENCES return_order_line (return_line_id) ON DELETE CASCADE
);
