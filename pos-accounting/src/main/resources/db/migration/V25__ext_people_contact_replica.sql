-- CAP:550 AP reads (#2670; ADR-0044 R1, §6; ADR-0064, ADR-0072): accounting's minimal copy of pos-people-contact's
-- persons and user-person links, written only by people-contact.events.v1 (PeopleContactEventsListener) and read only
-- to put a display name beside each AP actor's username. Accounting never joins another service's database and never
-- calls pos-people-contact or pos-security-service for a name.
--
-- Minimised (ADR-0072): a person's first and last name, nothing else (no preferred name, no contact point, no
-- address). The names are CONFIDENTIAL: served in responses, never logged. aggregate_version is the fact envelope's
-- (the emission time in epoch milliseconds for pos-people-contact): an older fact is ignored, an equal one applies, so a
-- manifest-driven replay repairs a row. A link carries no foreign key to its person: the two arrive as separate facts,
-- in either order, and a person may be deleted while a link still names it (the name is then null).
--
-- Removal is a tombstone, never a hard delete (#2676 review B5): person.deleted keeps the person's row with deleted =
-- true, its names cleared and the fact's version; user-person-link.removed keeps the link with status REMOVED and the
-- fact's version. A late, older person.updated or user-person-link.updated then finds a newer row and changes nothing,
-- so a removed user is never named again. A name resolves only through an ACTIVE link to a person not deleted.
--
-- Follows TENANCY_SCHEMA.md "Adding a table" (ADR-0062): tenant_id first, the (tenant_id, <pk>) key, the tenant index
-- and the tenant_isolation policy. No SQL clock (ADR-0024).

CREATE TABLE public.ext_people_contact_person (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    person_id uuid NOT NULL,
    first_name character varying(255),
    last_name character varying(255),
    deleted boolean DEFAULT false NOT NULL,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

ALTER TABLE ONLY public.ext_people_contact_person
    ADD CONSTRAINT ext_people_contact_person_pkey PRIMARY KEY (person_id);
ALTER TABLE ONLY public.ext_people_contact_person
    ADD CONSTRAINT ext_people_contact_person_tenant_key UNIQUE (tenant_id, person_id);
CREATE INDEX ext_people_contact_person_tenant_idx ON public.ext_people_contact_person USING btree (tenant_id);

ALTER TABLE public.ext_people_contact_person ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_people_contact_person FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_people_contact_person
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

CREATE TABLE public.ext_people_contact_user_link (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    link_id uuid NOT NULL,
    person_id uuid NOT NULL,
    username character varying(255) NOT NULL,
    status character varying(20) NOT NULL,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

ALTER TABLE ONLY public.ext_people_contact_user_link
    ADD CONSTRAINT ext_people_contact_user_link_pkey PRIMARY KEY (link_id);
ALTER TABLE ONLY public.ext_people_contact_user_link
    ADD CONSTRAINT ext_people_contact_user_link_tenant_key UNIQUE (tenant_id, link_id);
CREATE INDEX ext_people_contact_user_link_tenant_idx ON public.ext_people_contact_user_link USING btree (tenant_id);
-- The read: a page's usernames, through their ACTIVE link, in one query.
CREATE INDEX ext_people_contact_user_link_username_idx
    ON public.ext_people_contact_user_link USING btree (tenant_id, username) WHERE status = 'ACTIVE';

ALTER TABLE public.ext_people_contact_user_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_people_contact_user_link FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_people_contact_user_link
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
