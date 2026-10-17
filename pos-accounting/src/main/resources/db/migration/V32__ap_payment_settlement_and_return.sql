-- #2642 (Accounting ruling, 2026-10-17): AP payment settlement and returns.
-- * settlement_status: whether the gateway has settled the charge (IN_FLIGHT after a PENDING/AUTHORIZED acceptance,
--   SETTLED after a SUCCEEDED answer, RETURNED after the charge failed); null for CASH and pre-S42 rows.
-- * RETURN_PENDING / RETURNED: a charge that moved money and came back (AW42 applied to a payment). The mirror entry
--   is the journal-entry reversal of AP_PAYMENT:<paymentId> (return_journal_entry_id), dated by return_date_rule
--   (RETURN_DATE when the failure date's period is open, else DETECTION_DATE); RETURN_PENDING while the mirror waits
--   for the original posting or for a period remedy.
-- * ap_payment_allocation.reopened_at: the return keeps the allocations as history and marks them reopened; every
--   allocation sum excludes reopened rows, which restores each bill's open amount.
-- * ap_payment_return_request: the operator command's requestId replay table (the #2678 / V29 convention).
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
        ('VOIDED'::character varying)::text,
        ('RETURN_PENDING'::character varying)::text,
        ('RETURNED'::character varying)::text])));
ALTER TABLE public.ap_payment ADD COLUMN settlement_status character varying(20);
ALTER TABLE public.ap_payment
    ADD CONSTRAINT ap_payment_settlement_status_check CHECK (settlement_status IS NULL OR (settlement_status)::text = ANY (ARRAY[
        ('IN_FLIGHT'::character varying)::text,
        ('SETTLED'::character varying)::text,
        ('RETURNED'::character varying)::text]));
ALTER TABLE public.ap_payment ADD COLUMN settlement_checked_at timestamp(6) with time zone;
ALTER TABLE public.ap_payment ADD COLUMN settled_at timestamp(6) with time zone;
ALTER TABLE public.ap_payment ADD COLUMN returned_at timestamp(6) with time zone;
ALTER TABLE public.ap_payment ADD COLUMN return_date date;
ALTER TABLE public.ap_payment ADD COLUMN return_date_rule character varying(40);
ALTER TABLE public.ap_payment ADD COLUMN return_failure_code character varying(64);
ALTER TABLE public.ap_payment ADD COLUMN return_journal_entry_id uuid;
CREATE INDEX ap_payment_in_flight_idx ON public.ap_payment USING btree (tenant_id, settlement_checked_at)
    WHERE (settlement_status)::text = 'IN_FLIGHT'::text;

ALTER TABLE public.ap_payment_allocation ADD COLUMN reopened_at timestamp(6) with time zone;

CREATE TABLE public.ap_payment_return_request (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    return_request_id uuid NOT NULL,
    request_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    request_hash character varying(64) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL
);
ALTER TABLE ONLY public.ap_payment_return_request
    ADD CONSTRAINT ap_payment_return_request_pkey PRIMARY KEY (return_request_id);
ALTER TABLE ONLY public.ap_payment_return_request
    ADD CONSTRAINT ap_payment_return_request_tenant_key UNIQUE (tenant_id, return_request_id);
-- The caller's requestId is unique per tenant, never a global key (the #2678 convention).
ALTER TABLE ONLY public.ap_payment_return_request
    ADD CONSTRAINT uq_ap_payment_return_request_request UNIQUE (tenant_id, request_id);
CREATE INDEX ap_payment_return_request_tenant_idx ON public.ap_payment_return_request USING btree (tenant_id);
CREATE INDEX ap_payment_return_request_payment_idx
    ON public.ap_payment_return_request USING btree (tenant_id, payment_id);
ALTER TABLE public.ap_payment_return_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ap_payment_return_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ap_payment_return_request
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
