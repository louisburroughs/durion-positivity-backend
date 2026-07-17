-- Replay-dedupe constraints (#922): back the in-code idempotency guards with partial unique
-- indexes so two concurrent retries cannot both slip past the read-then-insert check:
--   * invoice_adjustments: at most one adjustment per (type, external_reference) when a
--     reference is supplied (InvoiceServiceImpl.applyAdjustment matches on the same pair);
--   * refund_records: at most one non-FAILED refund per external_reference — FAILED refunds
--     must stay retryable with the same reference, matching the in-code dedupe's FAILED
--     exclusion (PaymentReversalServiceImpl).
-- Postgres-only WHERE clause (partial index, pos-customer V16 precedent); the dev profile
-- runs H2 with ddl-auto create-drop and never executes Flyway migrations.

CREATE UNIQUE INDEX ux_invoice_adjustments_type_external_reference
    ON invoice_adjustments (type, external_reference)
    WHERE external_reference IS NOT NULL;

CREATE UNIQUE INDEX ux_refund_records_external_reference_active
    ON refund_records (external_reference)
    WHERE external_reference IS NOT NULL AND status <> 'FAILED';
