-- CAP:550 S17 (#2513), the vendor cash-on-delivery half (Accounting ruling AW41, 2026-10-07).
-- A VENDOR_COD drawer movement posts Dr 2000 / Cr 1095 at session close and creates an AP payment with method
-- CASH, unapplied until the vendor's approved bill is allocated to it. The payment remembers the session and
-- the movement it came from; one movement makes one payment.
ALTER TABLE public.ap_payment DROP CONSTRAINT ap_payment_payment_method_check;
ALTER TABLE public.ap_payment
    ADD CONSTRAINT ap_payment_payment_method_check CHECK (((payment_method)::text = ANY (ARRAY[
        ('ACH'::character varying)::text,
        ('CHECK'::character varying)::text,
        ('WIRE'::character varying)::text,
        ('CREDIT_CARD'::character varying)::text,
        ('CASH'::character varying)::text,
        ('OTHER'::character varying)::text])));
ALTER TABLE public.ap_payment ADD COLUMN register_session_id uuid;
ALTER TABLE public.ap_payment ADD COLUMN cash_movement_id uuid;
ALTER TABLE public.ap_payment
    ADD CONSTRAINT uq_ap_payment_cash_movement UNIQUE (tenant_id, cash_movement_id);

-- Allocating a CASH payment later (POST /ap/payments/{id}/allocations) is idempotent on the caller's requestId:
-- a replay with the same body answers the same payment, another body under the same id is 409.
-- Follows TENANCY_SCHEMA.md "Adding a table" (ADR-0062).
CREATE TABLE public.ap_payment_allocation_request (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    allocation_request_id uuid NOT NULL,
    request_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    request_hash character varying(64) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL
);
ALTER TABLE ONLY public.ap_payment_allocation_request
    ADD CONSTRAINT ap_payment_allocation_request_pkey PRIMARY KEY (allocation_request_id);
ALTER TABLE ONLY public.ap_payment_allocation_request
    ADD CONSTRAINT ap_payment_allocation_request_tenant_key UNIQUE (tenant_id, allocation_request_id);
-- The caller's requestId is unique per tenant, never a global key: a tenant's replay check never sees another
-- tenant's row under RLS, so another tenant reusing the same id must not collide (the #2678 convention).
ALTER TABLE ONLY public.ap_payment_allocation_request
    ADD CONSTRAINT uq_ap_payment_allocation_request_request UNIQUE (tenant_id, request_id);
CREATE INDEX ap_payment_allocation_request_tenant_idx ON public.ap_payment_allocation_request USING btree (tenant_id);
CREATE INDEX ap_payment_allocation_request_payment_idx
    ON public.ap_payment_allocation_request USING btree (tenant_id, payment_id);
ALTER TABLE public.ap_payment_allocation_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ap_payment_allocation_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ap_payment_allocation_request
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
