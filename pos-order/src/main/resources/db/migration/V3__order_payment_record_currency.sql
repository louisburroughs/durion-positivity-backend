-- ADR-0067 DF-3 (issue #2311): keep the currency of the settled or reversed payment on the order's
-- ledger entry, so a payment reversal states the currency it refunds in instead of an invented one.
--
-- Nullable: an ON_ACCOUNT entry (written by pos-order itself, no gateway leg) has no source
-- currency until the order carries a document currency (ADR-0067 Stage A, step A2); inventing one
-- is what this migration removes. Event-sourced rows (a payment intent or a refund on the row) are
-- backfilled with USD because every payment.payment.settled / payment.payment.reversed fact
-- pos-invoice has published so far was stamped USD (pos-invoice PaymentEventPublisher).
ALTER TABLE order_payment_record ADD COLUMN currency_code character varying(3);

UPDATE order_payment_record
   SET currency_code = 'USD'
 WHERE currency_code IS NULL
   AND (payment_intent_id IS NOT NULL OR refund_id IS NOT NULL);

COMMENT ON COLUMN order_payment_record.currency_code IS
    'ADR-0067 DF-3: ISO 4217 currency of the settled/reversed payment, from the pos-invoice fact; null for ON_ACCOUNT entries.';
