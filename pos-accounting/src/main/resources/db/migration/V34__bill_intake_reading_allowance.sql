-- CAP:550 S45 (#2619): pos-accounting's tenant-scoped replica of the tenant's monthly document-reading allowance,
-- fed by tenant.reading-allowance.changed on tenant.events.v1. One row per tenant, so the key is the tenant's own
-- id: reading_allowance_id holds the tenant id verbatim (the entity extends TenantScopedEntity, whose tenant_id is
-- Hibernate-stamped and cannot double as the @Id), and UNIQUE (tenant_id) keeps the one-row-per-tenant rule. A
-- tenant without a row has no known allowance; S26's meter then makes no provider call (fail closed).
CREATE TABLE public.bill_intake_reading_allowance (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    reading_allowance_id uuid NOT NULL,
    tier_code character varying(32) NOT NULL,
    amount numeric(19,4) NOT NULL,
    currency character varying(3) NOT NULL,
    effective_at timestamp(6) with time zone NOT NULL,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);
ALTER TABLE ONLY public.bill_intake_reading_allowance
    ADD CONSTRAINT bill_intake_reading_allowance_pkey PRIMARY KEY (reading_allowance_id);
ALTER TABLE ONLY public.bill_intake_reading_allowance
    ADD CONSTRAINT bill_intake_reading_allowance_tenant_key UNIQUE (tenant_id, reading_allowance_id);
ALTER TABLE ONLY public.bill_intake_reading_allowance
    ADD CONSTRAINT uq_bill_intake_reading_allowance_one_row UNIQUE (tenant_id);
-- No separate tenant_id index: uq_bill_intake_reading_allowance_one_row already indexes it.
ALTER TABLE public.bill_intake_reading_allowance ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bill_intake_reading_allowance FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.bill_intake_reading_allowance
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
