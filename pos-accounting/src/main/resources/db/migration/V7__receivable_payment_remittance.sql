-- #2502 (CAP:550 S1; SPEC-accounting-workspace §4.4 items 4-6, §5.3, §7.1 "Unapplied payments" and
-- "Eligible invoices"): what the unapplied-payments list shows and how a customer's open invoices are
-- found without a scan.
--
-- 1. A receivable payment records the invoice it was taken against (the remittance reference the
--    current sources carry) and the settlement method, as the payment.payment.settled fact sends it
--    (CASH, CARD, ON_ACCOUNT, OTHER). Both are written from now on by the two paths that record a
--    payment; the first writer wins. Rows recorded before this migration stay NULL: nothing is
--    backfilled (pre-production).
-- 2. A customer's open invoices are read by (party, status) under row-level security, so the index
--    leads with tenant_id (TENANCY_SCHEMA.md "Adding a table" step 2).

ALTER TABLE public.receivable_payment ADD COLUMN source_invoice_id uuid;
ALTER TABLE public.receivable_payment ADD COLUMN payment_method character varying(20);

COMMENT ON COLUMN public.receivable_payment.source_invoice_id IS
    'Invoice the payment was taken against, when the recording fact names one (#2502). NULL when '
    'unknown or recorded before the column existed.';
COMMENT ON COLUMN public.receivable_payment.payment_method IS
    'Settlement method as the payment.payment.settled fact sends it: CASH, CARD, ON_ACCOUNT or OTHER '
    '(#2502). NULL when the recording path carries no method.';

CREATE INDEX idx_ext_invoice_party_status ON public.ext_invoice USING btree (tenant_id, party_id, status);
