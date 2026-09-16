-- CAP-328 (durion#485, spec D6, D7, §4.4): the credential a person holds -- the first-class aggregate.
--
-- A skill is reference data; a credential has an issuer, an issue date, an expiry, a lifecycle and
-- retained evidence, and it belongs to pos-people (DECISION-SHOPMGMT-009: mechanic identity is a
-- foreign reference to the People domain; shopmgmt does not own mechanic data). pos-shop-manager
-- holds a replica of these rows and stores none of its own.
--
--   * Natural key (person, skill, issuer, issued_on): a renewal is a NEW row, never an overwrite,
--     so "was this person qualified on date X" stays answerable after the renewal -- 49 CFR 396.19
--     requires the shop to retain evidence of an inspector's qualification, and an auditor asks
--     about a past date.
--   * status is derived from expires_on at write and recomputed on read, never trusted from a feed;
--     REVOKED and SUPERSEDED are the two states a date cannot produce. A row a feed stops sending is
--     marked SUPERSEDED with the superseding job recorded -- never deleted.
--   * expires_on is nullable: not every credential expires, and null must never read as expired.
--   * proficiency is display metadata only (spec D7): no requirement thresholds on it.
--   * skill_id references the platform-global registry with a single-column FK (TENANCY_SCHEMA.md
--     step 2 applies only to FKs into scoped tables).
CREATE TABLE public.person_credential (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    id uuid NOT NULL,
    person_id uuid NOT NULL,
    skill_id uuid NOT NULL,
    issuer character varying(32) NOT NULL,
    source_code character varying(32),
    source_credential_code character varying(128),
    issued_on date NOT NULL,
    expires_on date,
    proficiency integer,
    status character varying(16) NOT NULL,
    evidence_ref uuid,
    source_system character varying(64),
    source_version character varying(64),
    superseded_by character varying(128),
    created_by character varying(255),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT person_credential_pkey PRIMARY KEY (id),
    CONSTRAINT person_credential_tenant_key UNIQUE (tenant_id, id),
    CONSTRAINT person_credential_natural_key UNIQUE (tenant_id, person_id, skill_id, issuer, issued_on),
    CONSTRAINT person_credential_status_check
        CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED', 'SUPERSEDED')),
    CONSTRAINT person_credential_proficiency_check
        CHECK (proficiency IS NULL OR proficiency BETWEEN 1 AND 5),
    CONSTRAINT person_credential_expiry_after_issue_check
        CHECK (expires_on IS NULL OR expires_on >= issued_on),
    CONSTRAINT fk_person_credential_skill FOREIGN KEY (skill_id) REFERENCES public.skill(id)
);
CREATE INDEX person_credential_tenant_idx ON public.person_credential USING btree (tenant_id);
CREATE INDEX person_credential_person_idx ON public.person_credential USING btree (tenant_id, person_id);
CREATE INDEX person_credential_skill_status_idx
    ON public.person_credential USING btree (tenant_id, skill_id, status);
ALTER TABLE public.person_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.person_credential FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.person_credential
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
COMMENT ON TABLE public.person_credential IS
    'CAP-328: a credential a person holds. Natural key (person, skill, issuer, issued_on); a renewal inserts. status derives from expires_on; SUPERSEDED marks a row the feed stopped sending, never deleted.';
