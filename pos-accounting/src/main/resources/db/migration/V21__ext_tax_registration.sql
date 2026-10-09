-- CAP:550 S32c (#2638; ADR-0071 §7, ADR-0044 R3, AW58/AW49): accounting's copy of pos-tax's tenant tax
-- registrations, written only by tax.registration.changed on tax.events.v1 and keyed by the fact's aggregate (the
-- pos-tax registration id). aggregate_version guards it (ReplicaVersionGuard): an older fact is ignored, an equal one
-- applies, so a manifest-driven replay repairs a row. Read as of a business date by its inclusive effective dates;
-- the front door's GET /v1/accounting/tax-registrations reads it, and S32d's recovery flags will.
--
-- registration_number holds the shape-checked, normalised number pos-tax published. It is INTERNAL under ADR-0072
-- Decision 1 (conditions a-c; Security on louisburroughs/durion#571), so the copy may hold it; it is never logged.
--
-- Follows TENANCY_SCHEMA.md "Adding a table" (ADR-0062): tenant_id first, the (tenant_id, <pk>) key, the tenant
-- index and the tenant_isolation policy. No SQL clock (ADR-0024).

CREATE TABLE public.ext_tax_registration (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    registration_id uuid NOT NULL,
    country_code character varying(2) NOT NULL,
    regime character varying(32) NOT NULL,
    registration_number character varying(32) NOT NULL,
    jurisdiction_code character varying(32) NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    aggregate_version bigint NOT NULL,
    changed_at timestamp(6) with time zone NOT NULL,
    synced_at timestamp(6) with time zone NOT NULL
);

ALTER TABLE ONLY public.ext_tax_registration
    ADD CONSTRAINT ext_tax_registration_pkey PRIMARY KEY (registration_id);
ALTER TABLE ONLY public.ext_tax_registration
    ADD CONSTRAINT ext_tax_registration_tenant_key UNIQUE (tenant_id, registration_id);
CREATE INDEX ext_tax_registration_tenant_idx ON public.ext_tax_registration USING btree (tenant_id);
CREATE INDEX idx_ext_tax_registration_regime
    ON public.ext_tax_registration USING btree (tenant_id, country_code, regime, effective_from);

ALTER TABLE public.ext_tax_registration ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_tax_registration FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_tax_registration
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
