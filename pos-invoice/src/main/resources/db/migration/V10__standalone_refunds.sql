-- Standalone refunds (#926):
--   * refund_records no longer requires a captured PaymentIntent — walk-in warranty claims on
--     cash sales from predecessor systems, pre-deploy invoices, and vendor-paid scenarios have
--     no PaymentIntent to anchor to;
--   * payment_intent_id and invoice_id become nullable, a party_id anchor is added for
--     no-invoice cases, and a check constraint guarantees every refund keeps at least one
--     anchor (payment intent, invoice, or party).
-- H2-compatible: plain ADD COLUMN, ALTER COLUMN ... DROP NOT NULL, and named-constraint ADD only.

ALTER TABLE refund_records ALTER COLUMN payment_intent_id DROP NOT NULL;

ALTER TABLE refund_records ALTER COLUMN invoice_id DROP NOT NULL;

ALTER TABLE refund_records ADD COLUMN party_id character varying(64);

ALTER TABLE refund_records ADD CONSTRAINT refund_records_anchor_check
    CHECK (payment_intent_id IS NOT NULL OR invoice_id IS NOT NULL OR party_id IS NOT NULL);
