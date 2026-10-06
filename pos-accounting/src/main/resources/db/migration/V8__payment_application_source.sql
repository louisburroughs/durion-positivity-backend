-- #2503 (CAP:550 S2; SPEC-accounting-workspace §4.4 item 4, §5.3 item 4, §7.1 "Automatic application"
-- and "Done automatically"; AW14): every payment application records the path that created it, so the
-- workspace can list what was matched automatically, with an undo.
--
-- 1. payment_application.application_source: MANUAL (a person, POST .../payments/{id}/applications),
--    PAYMENT_SETTLED (applied when the payment settled) or INVOICE_PAYMENT (the INVOICE_PAYMENT
--    event processor, #2435). Set once at creation by each path.
-- 2. Backfill: an application whose request id starts with INVOICE_PAYMENT: was created by the
--    INVOICE_PAYMENT processor; every other existing application was made by hand. No settled payment
--    was ever applied automatically before this migration (BR-9: no backfill of payments).
--    payment_application is under FORCE ROW LEVEL SECURITY; the owner running Flyway would otherwise
--    see only its bound tenant (none here), so the backfill lifts FORCE for its own statement and
--    restores it. The whole migration is one transaction: nobody observes the table without it.
-- 3. The automatic-applications read filters by (source, applied time) under row-level security, so
--    its index leads with tenant_id (TENANCY_SCHEMA.md "Adding a table" step 2).

ALTER TABLE public.payment_application ADD COLUMN application_source character varying(20);

ALTER TABLE public.payment_application NO FORCE ROW LEVEL SECURITY;

UPDATE public.payment_application
SET application_source = CASE
        WHEN application_request_id LIKE 'INVOICE\_PAYMENT:%' ESCAPE '\' THEN 'INVOICE_PAYMENT'
        ELSE 'MANUAL'
    END;

ALTER TABLE public.payment_application FORCE ROW LEVEL SECURITY;

ALTER TABLE public.payment_application ALTER COLUMN application_source SET NOT NULL;

ALTER TABLE public.payment_application
    ADD CONSTRAINT ck_payment_application_source
        CHECK (application_source IN ('MANUAL', 'PAYMENT_SETTLED', 'INVOICE_PAYMENT'));

COMMENT ON COLUMN public.payment_application.application_source IS
    'Path that created the application (#2503): MANUAL, PAYMENT_SETTLED or INVOICE_PAYMENT. Set at '
    'creation, never changed; rows created before the column existed were backfilled from the request '
    'id (INVOICE_PAYMENT: prefix -> INVOICE_PAYMENT, else MANUAL).';

CREATE INDEX idx_payment_application_source_ts
    ON public.payment_application USING btree (tenant_id, application_source, application_timestamp);
