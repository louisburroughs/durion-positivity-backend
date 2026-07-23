-- Parity story C2 (#1070): counter-sale invoices created from a sales order.
-- An order-fronted invoice has no workorder, so workorder_id becomes nullable; order_id is the
-- idempotency anchor for the from-order creation path (one invoice per sales order).

ALTER TABLE invoices ALTER COLUMN workorder_id DROP NOT NULL;

ALTER TABLE invoices ADD COLUMN order_id UUID;

CREATE UNIQUE INDEX ux_invoices_order_id ON invoices (order_id) WHERE order_id IS NOT NULL;
