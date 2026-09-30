-- #2122: a tenant-scoped replica of pos-people's credential aggregate, fed by
-- people.person-credential.updated on people.events.v1 (ADR-0044 §6), so the dispatch board can
-- tell which certifications a mechanic holds without a synchronous cross-domain read. Before this
-- table PersonAvailability.certifications was never populated, and every workorder that listed
-- required certifications warned MECHANIC_SKILL_MISMATCH against every mechanic.
--
-- Same shape as pos-shop-manager's ext_person_credential: one row per credential fact, keyed by
-- the owner's credential id and guarded by aggregate_version. The feed's status is stored as
-- received; readers judge expiry from expires_on against the date they are asking about. The table
-- starts empty and stays empty until a credential fact arrives; readers treat a person with no row
-- as "no credential data" (the skill check stays silent), never as "holds nothing".
--
-- Depends on V11__ext_people_employee.sql (employment branch): merge after it.

CREATE TABLE public.ext_person_credential (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    credential_id uuid NOT NULL,
    person_id uuid NOT NULL,
    skill_id uuid NOT NULL,
    skill_code character varying(64) NOT NULL,
    competence_code character varying(64) NOT NULL,
    min_gvwr_class integer NOT NULL,
    max_gvwr_class integer NOT NULL,
    issuer character varying(32) NOT NULL,
    source_code character varying(32),
    source_credential_code character varying(128),
    issued_on date NOT NULL,
    expires_on date,
    proficiency integer,
    status character varying(16) NOT NULL,
    evidence_ref uuid,
    superseded_by character varying(128),
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_person_credential_pkey PRIMARY KEY (credential_id),
    CONSTRAINT ext_person_credential_tenant_key UNIQUE (tenant_id, credential_id)
);

CREATE INDEX ext_person_credential_tenant_idx ON public.ext_person_credential USING btree (tenant_id);
CREATE INDEX ext_person_credential_tenant_person_idx ON public.ext_person_credential USING btree (tenant_id, person_id, status);

ALTER TABLE public.ext_person_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_person_credential FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_person_credential
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
