-- #2729 (Accounting ruling, 2026-10-17): AP payments stuck before posting.
-- * VOIDED: a GATEWAY_PENDING orphan (a crash or timeout between the gateway call and its answer) whose operator attests
--   that no money moved. Nothing to post, nothing to reopen; the void is audited (accounting_audit_log).
-- * posting_date_rule: DEFERRED_PAST_HARD_LOCK when a GL_POST_FAILED payment whose payment_date is hard-locked was
--   posted on the retry's business date instead (AW42: never back into the original period).
ALTER TABLE public.ap_payment DROP CONSTRAINT ap_payment_status_check;
ALTER TABLE public.ap_payment
    ADD CONSTRAINT ap_payment_status_check CHECK (((status)::text = ANY (ARRAY[
        ('INITIATED'::character varying)::text,
        ('GATEWAY_PENDING'::character varying)::text,
        ('GATEWAY_FAILED'::character varying)::text,
        ('GATEWAY_SUCCEEDED'::character varying)::text,
        ('GL_POST_PENDING'::character varying)::text,
        ('GL_POSTED'::character varying)::text,
        ('GL_POST_FAILED'::character varying)::text,
        ('VOIDED'::character varying)::text])));
ALTER TABLE public.ap_payment ADD COLUMN posting_date_rule character varying(40);
ALTER TABLE public.ap_payment ADD COLUMN void_reason character varying(1000);
ALTER TABLE public.ap_payment ADD COLUMN voided_at timestamp(6) with time zone;
ALTER TABLE public.ap_payment ADD COLUMN voided_by character varying(50);
