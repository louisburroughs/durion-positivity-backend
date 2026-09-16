-- ext_catalog_service: event-fed replica of pos-catalog's service vocabulary (ADR-0044 §6).
--
-- Fed by catalog.service.updated on catalog.events.v1, which already exists at schema v2 and
-- already carries serviceId, name and operationCode — so this replica needs no new fact.
--
-- Exists so a bay's specialty claim (CAP-325 D14) can be validated against the catalog operation
-- vocabulary without a synchronous cross-module read. operation_code is the column the specialty
-- map joins against, hence the partial index.
--
-- Tenant-scoped per docs/TENANCY_SCHEMA.md; deliberately NOT in db/tenancy-global-tables.txt.

CREATE TABLE public.ext_catalog_service (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    service_id uuid NOT NULL,
    name character varying(255),
    operation_code character varying(64),
    active boolean DEFAULT false NOT NULL,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

ALTER TABLE ONLY public.ext_catalog_service
    ADD CONSTRAINT ext_catalog_service_pkey PRIMARY KEY (service_id);

ALTER TABLE ONLY public.ext_catalog_service
    ADD CONSTRAINT ext_catalog_service_tenant_key UNIQUE (tenant_id, service_id);

-- The specialty map resolves by (tenant_id, operation_code); partial because pos-catalog's
-- operation_code is nullable and a service without one can never be a specialty claim.
CREATE INDEX ix_ext_catalog_service_operation_code ON public.ext_catalog_service
    USING btree (tenant_id, operation_code) WHERE (operation_code IS NOT NULL);

CREATE INDEX ext_catalog_service_tenant_idx ON public.ext_catalog_service USING btree (tenant_id);

ALTER TABLE public.ext_catalog_service ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_catalog_service FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_catalog_service
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
