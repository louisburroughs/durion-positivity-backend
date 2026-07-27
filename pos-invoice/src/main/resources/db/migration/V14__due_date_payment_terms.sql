-- #993 (PR #1005 review): due-date aging support, kept out of the immutable V1/V7
-- versioned migrations — additive migration instead so already-migrated environments
-- do not fail Flyway checksum validation.
--
--   * billing_rules.payment_terms_code becomes a closed vocabulary (PaymentTerms enum);
--     any legacy free-form codes are normalized to the global default NET_30 before the
--     CHECK is enforced.
--   * invoices gains due_date + payment_terms_code, frozen at finalization. Rows already
--     FINALIZED/POSTED are backfilled as due-on-receipt anchored to their finalization
--     instant's date — the same semantics as the pre-#993 finalizedAt aging fallback —
--     before the CHECK is enforced.
--   * ext_location gains the location timezone used to anchor due-date computation.
-- (The dev profile runs H2 with ddl-auto and never executes Flyway migrations —
-- V12 precedent — so this file targets Postgres.)

UPDATE billing_rules
   SET payment_terms_code = 'NET_30'
 WHERE payment_terms_code NOT IN ('DUE_ON_RECEIPT', 'NET_10', 'NET_15', 'NET_30', 'NET_45', 'NET_60');

ALTER TABLE billing_rules ADD CONSTRAINT billing_rules_payment_terms_code_check
    CHECK (payment_terms_code IN ('DUE_ON_RECEIPT', 'NET_10', 'NET_15', 'NET_30', 'NET_45', 'NET_60'));

ALTER TABLE invoices ADD COLUMN due_date date;
ALTER TABLE invoices ADD COLUMN payment_terms_code character varying(20);

-- Backfill invoices finalized before this feature (best-effort UTC-session date;
-- created_at guards the defensive case of a FINALIZED row without finalized_at).
UPDATE invoices
   SET due_date = CAST(COALESCE(finalized_at, created_at) AS date),
       payment_terms_code = 'DUE_ON_RECEIPT'
 WHERE status IN ('FINALIZED', 'POSTED')
   AND due_date IS NULL;

ALTER TABLE invoices ADD CONSTRAINT invoices_finalized_due_date_check
    CHECK (status NOT IN ('FINALIZED', 'POSTED') OR (due_date IS NOT NULL AND payment_terms_code IS NOT NULL));

ALTER TABLE ext_location ADD COLUMN timezone varchar(64);
