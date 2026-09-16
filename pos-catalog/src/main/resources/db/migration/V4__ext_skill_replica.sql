-- CAP-329: a replica of the People domain's skill registry, so a service's skill requirement
-- can be validated here without a synchronous call into pos-people (ADR-0044 §6).
--
-- The registry is @TenantGlobal reference data (CAP-328): one vocabulary for every tenant, so
-- the replica is global too — no tenant_id, no row-level security, listed in
-- db/tenancy-global-tables.txt. Fed by people.skill.updated on people.events.v1 and written
-- only by PeopleEventsListener. active=false is a retirement the consumer must be able to
-- name, not a deletion.
CREATE TABLE public.ext_skill (
    skill_id uuid NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(255) NOT NULL,
    competence_code character varying(64) NOT NULL,
    min_gvwr_class integer NOT NULL,
    max_gvwr_class integer NOT NULL,
    active boolean NOT NULL,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_skill_pkey PRIMARY KEY (skill_id),
    CONSTRAINT ext_skill_code_key UNIQUE (code)
);
