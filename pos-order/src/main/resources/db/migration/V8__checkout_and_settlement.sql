-- Wave 3 (odoo parity #1071/#1072): checkout handshake and settlement read model.

-- C1 (#1071): checkout freeze — invoice reference, idempotency, running settlement aggregates
ALTER TABLE sales_order ADD COLUMN invoice_id UUID;
ALTER TABLE sales_order ADD COLUMN invoice_number VARCHAR(64);
ALTER TABLE sales_order ADD COLUMN checkout_idempotency_key VARCHAR(128);
ALTER TABLE sales_order ADD COLUMN amount_paid NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE sales_order ADD COLUMN balance_due NUMERIC(19, 4);
CREATE UNIQUE INDEX ux_sales_order_checkout_key ON sales_order (checkout_idempotency_key)
  WHERE checkout_idempotency_key IS NOT NULL;

-- C3 (#1072): the cancellation saga now iterates payment records; the single-payment column goes
ALTER TABLE sales_order DROP COLUMN payment_id;

-- C3 (#1072): settlement ledger fed by payment.events.v1 (SETTLED and REVERSED entries;
-- amountPaid = sum(SETTLED) - sum(REVERSED))
CREATE TABLE order_payment_record (
  payment_record_id UUID PRIMARY KEY,
  order_id UUID NOT NULL,
  record_type VARCHAR(16) NOT NULL,
  payment_intent_id UUID,
  refund_id UUID,
  method_type VARCHAR(32) NOT NULL,
  amount NUMERIC(19, 4) NOT NULL,
  reference VARCHAR(128),
  occurred_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_order_payment_record_order ON order_payment_record (order_id);
CREATE INDEX idx_order_payment_record_intent ON order_payment_record (payment_intent_id);
