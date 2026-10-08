-- CAP:550 S42 (#2603; SPEC-accounting-workspace §4.3 "AP payments (AW41)", §7.1; AW40, AW41): AP payments post
-- through the AP_PAYMENT posting category, from the bank account they were paid from, on the day they executed.
--
-- * payment_date becomes the tenant business date the payment executed on (DATE), fixed before the gateway call: the
--   date its period check used, the date its entry posts on and the date a retry posts on. No payment has ever posted
--   (AP_PAYMENT_GL_POSTING always failed NO_RULE_VERSION) and the pay command never wrote the column, so the cast
--   changes no booked date.
-- * bank_account_id, the BANK_CASH account the payment was made from and the credit side of its entry, gains its
--   foreign key to gl_account (composite on tenant_id, ADR-0062).
-- * A closed-period override accepted on the pay command travels with the payment: its posting applies it as the
--   payer, whose name the override audit row carries.
-- * net_amount is dropped: the bank pays gross + fee, so "net deposited" has no meaning for an AP payment
--   (ruling 7 of #2603). Pre-production: no shim.
-- * AP_PAYMENT_GL_POSTING is retired (AW40): every such event not already PROCESSED is closed SKIPPED with
--   failureReasonCode RETIRED_EVENT_TYPE, which neither the retry job nor the retry endpoint selects, as V17 did for
--   VENDOR_BILL_GL_POSTING.
--
-- Tenancy: same table, same row-level security policy; Flyway runs as the owning superuser, so the event clean-up
-- reaches every tenant's rows. No timestamp is written (ADR-0024). The AP_PAYMENT category, its keys and mappings are
-- template rows in R__seed_reference_accounting.sql, provisioned to every tenant by S37's applier and startup sweep.

ALTER TABLE public.ap_payment ALTER COLUMN payment_date TYPE date USING payment_date::date;

ALTER TABLE public.ap_payment DROP COLUMN net_amount;

ALTER TABLE public.ap_payment ADD COLUMN period_override_justification character varying(1000);
ALTER TABLE public.ap_payment ADD COLUMN period_override_by character varying(50);

COMMENT ON COLUMN public.ap_payment.payment_date IS
    'The tenant business date the payment executed on, fixed before the gateway call; its entry posts on it and is '
    'never re-dated (CAP:550 S42, AW41).';
COMMENT ON COLUMN public.ap_payment.bank_account_id IS
    'The BANK_CASH GL account the payment was made from: the credit side of its entry (AW41).';
COMMENT ON COLUMN public.ap_payment.period_override_justification IS
    'Justification of a closed-period override accepted on the pay command; applied to the payment''s posting.';
COMMENT ON COLUMN public.ap_payment.period_override_by IS
    'Who gave the closed-period override (the payer); named by the posting''s override audit row.';

ALTER TABLE ONLY public.ap_payment
    ADD CONSTRAINT ap_payment_bank_account_fk FOREIGN KEY (tenant_id, bank_account_id)
        REFERENCES public.gl_account (tenant_id, gl_account_id);

CREATE INDEX ap_payment_bank_account_idx ON public.ap_payment USING btree (tenant_id, bank_account_id);

UPDATE public.accounting_event
SET status = 'SKIPPED',
    failure_reason_code = 'RETIRED_EVENT_TYPE',
    error_message = 'AP_PAYMENT_GL_POSTING is retired (AW40): an AP payment posts from the outbox through the '
        || 'AP_PAYMENT posting category; this event is obsolete and is never retried'
WHERE event_type = 'AP_PAYMENT_GL_POSTING'
  AND status <> 'PROCESSED';
