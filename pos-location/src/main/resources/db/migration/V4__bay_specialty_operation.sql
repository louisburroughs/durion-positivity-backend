-- CAP-325 D14: the specialty map. Which catalog operation codes a bay type is the ONLY type able
-- to perform. Everything not in this table is general work, done in a GENERAL_SERVICE bay, and
-- general bays declare nothing. This is the whole of bay-eligibility configuration.
--
-- operation_code is a catalog code (UPPER-DASH, ADR-0059 §3) validated against
-- ext_catalog_service on write. Deliberately no FK: the vocabulary is owned by pos-catalog and
-- reaches this module only as a replica (ADR-0044 §6).
--
-- Tenant-scoped per ../durion/docs/architecture/deployment/TENANCY_SCHEMA.md — a tenant's shops may confine work differently — with
-- the platform default supplied by R__seed_location_2_bay_specialty.sql.

CREATE TABLE public.bay_specialty_operation (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    bay_type character varying(50) NOT NULL,
    operation_code character varying(64) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

ALTER TABLE ONLY public.bay_specialty_operation
    ADD CONSTRAINT bay_specialty_operation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.bay_specialty_operation
    ADD CONSTRAINT bay_specialty_operation_tenant_key UNIQUE (tenant_id, id);

ALTER TABLE ONLY public.bay_specialty_operation
    ADD CONSTRAINT uq_bay_specialty_operation UNIQUE (tenant_id, bay_type, operation_code);

-- The two lookups: "what does a bay of this type default to" and "which types claim this code".
CREATE INDEX ix_bay_specialty_operation_type ON public.bay_specialty_operation
    USING btree (tenant_id, bay_type);
CREATE INDEX ix_bay_specialty_operation_code ON public.bay_specialty_operation
    USING btree (tenant_id, operation_code);

CREATE INDEX bay_specialty_operation_tenant_idx ON public.bay_specialty_operation USING btree (tenant_id);

ALTER TABLE public.bay_specialty_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bay_specialty_operation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.bay_specialty_operation
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
