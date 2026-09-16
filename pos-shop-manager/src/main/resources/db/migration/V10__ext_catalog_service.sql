-- CAP-329 (3/3): the catalog service replica this module schedules against (spec §4.6).
--
-- pos-catalog owns the service and its skill requirement; catalog.service.updated (schema v3)
-- carries both, and this module holds them here so a booking can resolve
-- (service, vehicle GVWR class) → required skills → competent rostered mechanics from replicas
-- alone, with no synchronous call (ADR-0044 §6). Written only by CatalogEventsListener.
--
-- requirements_configured_at NULL means the service's requirements were never configured — a
-- consumer warns, never denies (D4); a row with a timestamp and no skill children is a service
-- declared unconstrained. The skill children are a replace-set per fact: both class bounds NULL
-- means the requirement applies to every vehicle (ANY), a range means only to vehicles whose
-- class falls inside it (D8/D13).
CREATE TABLE public.ext_catalog_service (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    service_id uuid NOT NULL,
    name character varying(255),
    operation_code character varying(64),
    active boolean DEFAULT false NOT NULL,
    requirements_configured_at timestamp(6) with time zone,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_catalog_service_pkey PRIMARY KEY (service_id),
    CONSTRAINT ext_catalog_service_tenant_key UNIQUE (tenant_id, service_id)
);

CREATE TABLE public.ext_catalog_service_skill (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    service_id uuid NOT NULL,
    skill_id uuid NOT NULL,
    skill_code character varying(64) NOT NULL,
    min_gvwr_class integer,
    max_gvwr_class integer,
    CONSTRAINT ext_catalog_service_skill_pkey PRIMARY KEY (id),
    CONSTRAINT ext_catalog_service_skill_tenant_key UNIQUE (tenant_id, id),
    CONSTRAINT ext_catalog_service_skill_service_fkey
        FOREIGN KEY (tenant_id, service_id) REFERENCES public.ext_catalog_service(tenant_id, service_id) ON DELETE CASCADE
);

CREATE INDEX ext_catalog_service_tenant_idx ON public.ext_catalog_service USING btree (tenant_id);
CREATE INDEX ext_catalog_service_skill_tenant_idx ON public.ext_catalog_service_skill USING btree (tenant_id);
CREATE INDEX idx_sm_ext_catalog_service_skill_service ON public.ext_catalog_service_skill USING btree (service_id);

ALTER TABLE public.ext_catalog_service ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_catalog_service FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_catalog_service
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

ALTER TABLE public.ext_catalog_service_skill ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_catalog_service_skill FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_catalog_service_skill
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
