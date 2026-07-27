-- Story A1 (odoo parity #1059): optimistic locking + status transition history.
ALTER TABLE sales_order ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS order_status_history (
  history_id UUID PRIMARY KEY,
  order_id UUID NOT NULL,
  from_status VARCHAR(64),
  to_status VARCHAR(64) NOT NULL,
  actor VARCHAR(255) NOT NULL,
  reason VARCHAR(500),
  transitioned_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES sales_order(order_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_order_status_history_order_id ON order_status_history (order_id);
