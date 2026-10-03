-- pos-platform-sender: Flyway baseline, written with the ADR-0062 tenancy schema from the start
-- (../durion/docs/architecture/deployment/TENANCY_SCHEMA.md, "Adding a table"). Global (unscoped)
-- tables: src/main/resources/db/tenancy-global-tables.txt.
--
-- Tenant context: app_current_tenant() reads the session setting app.current_tenant, which the
-- TenantAwareDataSource (pos-tenancy-common) binds per checkout. With nothing bound it is NULL:
-- every scoped table reads as empty and refuses inserts (fail closed).

SET check_function_bodies = false;

CREATE OR REPLACE FUNCTION public.app_current_tenant() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
    AS $$ SELECT NULLIF(current_setting('app.current_tenant', true), '')::uuid $$;

-- One row per send request (FI-2 §1). message_id is the caller's idempotency key: a replay is
-- answered from this row instead of reaching the provider again. The row never holds the raw
-- address, only its SHA-256.
CREATE TABLE public.sent_message (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    sent_message_id uuid NOT NULL,
    message_id uuid NOT NULL,
    channel character varying(8) NOT NULL,
    recipient_party_id uuid NOT NULL,
    contact_id uuid,
    campaign_code character varying(100) NOT NULL,
    status character varying(16) NOT NULL,
    provider_message_id character varying(255),
    address_hash character varying(64),
    failure_code character varying(64),
    failure_reason character varying(1000),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT sent_message_channel_chk CHECK (((channel)::text = ANY ((ARRAY['EMAIL'::character varying, 'SMS'::character varying])::text[]))),
    CONSTRAINT sent_message_status_chk CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACCEPTED'::character varying, 'REJECTED'::character varying])::text[])))
);

-- ADR-0044 R3 replica of pos-customer's person parties: which pos-people-contact person a CRM
-- person party is. Fed by customer.events.v1 (customer.party.updated / customer.party.deleted).
-- A deleted party is a versioned tombstone (person_id NULL), so a replayed older update cannot
-- resurrect it.
CREATE TABLE public.ext_customer_person_party (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    party_id uuid NOT NULL,
    person_id uuid,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

-- ADR-0044 R3 replica of pos-people-contact's person contact points, reduced to the two this
-- module delivers to: the person's email and mobile phone, as stored by the owner (normalized at
-- send time). Fed by people-contact.events.v1 (person.updated / person.deleted); a deleted person
-- is a versioned tombstone with both addresses NULL.
CREATE TABLE public.ext_people_contact_person (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    person_id uuid NOT NULL,
    email character varying(255),
    mobile_phone character varying(255),
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

CREATE TABLE public.event_outbox (
    id uuid NOT NULL,
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    topic character varying(255) NOT NULL,
    record_key character varying(255) NOT NULL,
    payload text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    published_at timestamp(6) with time zone,
    attempts integer DEFAULT 0 NOT NULL,
    last_error text
);

COMMENT ON COLUMN public.event_outbox.tenant_id IS
    'ADR-0062: producing tenant, carried as data (global table, no policy); stamped on the Kafka record header.';

CREATE TABLE public.processed_events (
    event_id character varying(64) NOT NULL,
    tenant_id uuid,
    owner character varying(64) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL
);

ALTER TABLE ONLY public.sent_message
    ADD CONSTRAINT sent_message_pkey PRIMARY KEY (sent_message_id);

ALTER TABLE ONLY public.sent_message
    ADD CONSTRAINT sent_message_tenant_key UNIQUE (tenant_id, sent_message_id);

ALTER TABLE ONLY public.sent_message
    ADD CONSTRAINT uq_sent_message_message_id UNIQUE (tenant_id, message_id);

ALTER TABLE ONLY public.ext_customer_person_party
    ADD CONSTRAINT ext_customer_person_party_pkey PRIMARY KEY (party_id);

ALTER TABLE ONLY public.ext_customer_person_party
    ADD CONSTRAINT ext_customer_person_party_tenant_key UNIQUE (tenant_id, party_id);

ALTER TABLE ONLY public.ext_people_contact_person
    ADD CONSTRAINT ext_people_contact_person_pkey PRIMARY KEY (person_id);

ALTER TABLE ONLY public.ext_people_contact_person
    ADD CONSTRAINT ext_people_contact_person_tenant_key UNIQUE (tenant_id, person_id);

ALTER TABLE ONLY public.event_outbox
    ADD CONSTRAINT event_outbox_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.processed_events
    ADD CONSTRAINT processed_events_pkey PRIMARY KEY (event_id);

CREATE INDEX idx_event_outbox_unpublished ON public.event_outbox USING btree (id) WHERE (published_at IS NULL);

CREATE INDEX idx_processed_events_owner_tenant ON public.processed_events USING btree (owner, tenant_id, event_id);

-- Row-level security (ADR-0062 §2) on every tenant-scoped table.
ALTER TABLE public.sent_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sent_message FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.sent_message
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

ALTER TABLE public.ext_customer_person_party ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_customer_person_party FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_customer_person_party
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

ALTER TABLE public.ext_people_contact_person ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ext_people_contact_person FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.ext_people_contact_person
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());
