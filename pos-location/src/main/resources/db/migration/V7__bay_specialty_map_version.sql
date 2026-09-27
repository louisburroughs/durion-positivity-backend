-- DECISION-LOCATION-025: per-tenant version counter for the bay specialty map fact
-- (location.bay-specialty-map.updated). The map itself (bay_specialty_operation) is several rows,
-- not one aggregate with its own JPA @Version, so this table is the aggregate: one row per tenant,
-- bumped every time the map actually changes for that tenant (today, only tenant provisioning) and
-- read, never bumped, when the map is republished unchanged (the once-per-tenant startup sweep so
-- replicas fill). See BaySpecialtyMapPublisher.
--
-- Tenant-scoped per docs/TENANCY_SCHEMA.md; the unique constraint on tenant_id alone is what keeps
-- this to exactly one row per tenant.

CREATE TABLE public.bay_specialty_map_version (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

ALTER TABLE ONLY public.bay_specialty_map_version
    ADD CONSTRAINT bay_specialty_map_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.bay_specialty_map_version
    ADD CONSTRAINT bay_specialty_map_version_tenant_key UNIQUE (tenant_id, id);

ALTER TABLE ONLY public.bay_specialty_map_version
    ADD CONSTRAINT uq_bay_specialty_map_version_tenant UNIQUE (tenant_id);

CREATE INDEX bay_specialty_map_version_tenant_idx ON public.bay_specialty_map_version USING btree (tenant_id);

ALTER TABLE public.bay_specialty_map_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bay_specialty_map_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.bay_specialty_map_version
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
