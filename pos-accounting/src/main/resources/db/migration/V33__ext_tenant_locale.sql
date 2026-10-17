-- CAP:550 S44 (#2618): pos-accounting's tenant-scoped replica of the tenant's locale, fed by tenant.events.v1.
-- One row per tenant, so the key is the tenant's own id: ext_tenant_id holds the tenant id verbatim (the entity
-- extends TenantScopedEntity, whose tenant_id is Hibernate-stamped and cannot double as the @Id), and
-- UNIQUE (tenant_id) keeps the one-row-per-tenant rule. A tenant without a row has no known locale; the reader
-- answers en-US (ADR-0030 section 2).
CREATE TABLE public.ext_tenant (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    ext_tenant_id uuid NOT NULL,
    locale character varying(35),
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);
ALTER TABLE ONLY public.ext_tenant
    ADD CONSTRAINT ext_tenant_pkey PRIMARY KEY (ext_tenant_id);
ALTER TABLE ONLY public.ext_tenant
    ADD CONSTRAINT ext_tenant_tenant_key UNIQUE (tenant_id, ext_tenant_id);
ALTER TABLE ONLY public.ext_tenant
    ADD CONSTRAINT uq_ext_tenant_one_row UNIQUE (tenant_id);
-- No separate tenant_id index: uq_ext_tenant_one_row already indexes it.
ALTER TABLE public.ext_tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_tenant
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
