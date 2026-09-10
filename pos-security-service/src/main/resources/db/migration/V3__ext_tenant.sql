-- ADR-0062 section 7 (plan WS2b): the global ext_tenant replica of the public tenant projection
-- published by pos-tenant on tenant.events.v1. Login resolves a slug here before any tenant is
-- bound, GET /v1/tenants/me answers from it, and TenantIterator visits its ACTIVE rows; it is
-- therefore a global table (no tenant_id, no policy) listed in db/tenancy-global-tables.txt.
CREATE TABLE public.ext_tenant (
    tenant_id uuid NOT NULL,
    slug character varying(63) NOT NULL,
    display_name character varying(200),
    status character varying(16) NOT NULL,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

ALTER TABLE ONLY public.ext_tenant
    ADD CONSTRAINT ext_tenant_pkey PRIMARY KEY (tenant_id);
ALTER TABLE ONLY public.ext_tenant
    ADD CONSTRAINT ext_tenant_slug_key UNIQUE (slug);
CREATE INDEX idx_ext_tenant_status ON public.ext_tenant USING btree (status);

-- Replica bootstrap: the two tenants pos-tenant's own seed registers (its V2__seed_tenant.sql),
-- so slug resolution and the platform bootstrap work before the first tenant.events.v1 fact
-- flows. Version 0 so the first real fact (version >= 1) always wins.
INSERT INTO public.ext_tenant (tenant_id, slug, display_name, status, aggregate_version, updated_at)
VALUES ('01900000-0000-7000-8000-000000000000', 'platform', 'Durion Platform', 'ACTIVE', 0, now()),
       ('01900000-0000-7000-8000-000000000001', 'alpha', 'Alpha', 'ACTIVE', 0, now())
ON CONFLICT (tenant_id) DO NOTHING;
