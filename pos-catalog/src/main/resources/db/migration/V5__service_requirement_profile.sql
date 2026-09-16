-- CAP-329: a service declares the competence it requires, conditioned on the vehicle's GVWR
-- class (spec §4.1, §4.5; D8, D13). pos-catalog owns the requirement because it is an
-- attribute of the service (CAP-325 D1).
--
-- service_requirement_profile is the header: absent = the service's requirements are NOT
-- CONFIGURED (a consumer warns, never denies); present with no children = UNCONSTRAINED.
-- The two meanings must stay distinguishable, which is why an empty declaration still
-- writes the header.
--
-- service_skill_requirement is the profile's child. A row names a registry skill and the
-- GVWR class range it applies to; both class columns NULL means ANY — the requirement holds
-- for every vehicle, including one whose class is not yet known. The class sits here rather
-- than on a capability→skill map because (service, duty class) is the one place it is a
-- property of the pair: A5-BRAKES and T4-BRAKES are one competence on two vehicle classes,
-- and BRAKE-PAD-REPLACE-FRONT is one service consumed by both. Fork at the SKU when labor
-- time or price differs; class-condition the requirement when only competence differs.
--
-- No min_proficiency column (D7): a nullable unread column written by nothing is how the
-- old expiry columns reached their state. skill_id is validated against the ext_skill
-- replica on write, never a foreign key — the vocabulary is owned by pos-people.

CREATE TABLE public.service_requirement_profile (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    service_id uuid NOT NULL,
    configured_at timestamp(6) with time zone NOT NULL,
    configured_by character varying(255),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT service_requirement_profile_pkey PRIMARY KEY (service_id),
    CONSTRAINT service_requirement_profile_tenant_key UNIQUE (tenant_id, service_id),
    CONSTRAINT service_requirement_profile_service_id_fkey
        FOREIGN KEY (tenant_id, service_id) REFERENCES public.service(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE public.service_skill_requirement (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    service_id uuid NOT NULL,
    skill_id uuid NOT NULL,
    min_gvwr_class integer,
    max_gvwr_class integer,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT service_skill_requirement_pkey PRIMARY KEY (id),
    CONSTRAINT service_skill_requirement_tenant_key UNIQUE (tenant_id, id),
    CONSTRAINT service_skill_requirement_service_skill_key UNIQUE (tenant_id, service_id, skill_id),
    CONSTRAINT service_skill_requirement_profile_fkey
        FOREIGN KEY (tenant_id, service_id)
        REFERENCES public.service_requirement_profile(tenant_id, service_id) ON DELETE CASCADE,
    CONSTRAINT service_skill_requirement_class_range_check CHECK (
        (min_gvwr_class IS NULL AND max_gvwr_class IS NULL)
        OR (min_gvwr_class BETWEEN 1 AND 8 AND max_gvwr_class BETWEEN 1 AND 8
            AND min_gvwr_class <= max_gvwr_class))
);

CREATE INDEX service_requirement_profile_tenant_idx ON public.service_requirement_profile USING btree (tenant_id);
CREATE INDEX service_skill_requirement_tenant_idx ON public.service_skill_requirement USING btree (tenant_id);
CREATE INDEX idx_service_skill_requirement_service ON public.service_skill_requirement USING btree (service_id);

ALTER TABLE public.service_requirement_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.service_requirement_profile FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.service_requirement_profile
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

ALTER TABLE public.service_skill_requirement ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.service_skill_requirement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.service_skill_requirement
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
