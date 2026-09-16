-- CAP-328 (3/3): competence is read from a replica of the People domain's credential
-- aggregate, and this module stores none of it (louisburroughs/durion#485,
-- DECISION-SHOPMGMT-009).
--
-- ext_person_credential mirrors ext_people_staffing_assignment: one row per credential
-- fact on people.events.v1 (PersonCredentialUpdatedV1), keyed by the owner's id and
-- guarded by aggregate_version. The feed's status is stored as received, but expiry is
-- never trusted from it: readers derive ACTIVE/EXPIRED from expires_on against the date
-- they are asking about (a facility-local date for a roster, DECISION-SHOPMGMT-015).
--
-- mechanic_skill and certification are retired: two competence tables, neither with a
-- writer that could reach its date columns, and certification with no dates at all.
-- technician is retired with them: nothing wrote it, and it was the second person
-- identity in this module — Mechanic.person_id (varchar) joined to technician.person_id
-- (uuid) by CAST. Mechanic now carries the person id as the uuid it always was, so the
-- location roster joins the staffing-assignment replica directly.

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

CREATE INDEX idx_sm_ext_credential_person ON public.ext_person_credential USING btree (person_id, status);
CREATE INDEX ext_person_credential_tenant_idx ON public.ext_person_credential USING btree (tenant_id);

ALTER TABLE public.ext_person_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_person_credential FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_person_credential
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

-- certification references technician, so it goes first.
DROP TABLE public.certification;
DROP TABLE public.technician;
DROP TABLE public.mechanic_skill;

-- One person identity: the column always held a UUID rendered as text.
ALTER TABLE public.mechanic
    ALTER COLUMN person_id TYPE uuid USING person_id::uuid;
