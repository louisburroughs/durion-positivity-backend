-- ADR-0044 §4/§6 (#875): eventing infrastructure + identity replicas for the HR module.

-- Transactional outbox: people.events.v1 facts + people-contact.commands.v1 identity commands.
CREATE TABLE event_outbox (
    id uuid NOT NULL,
    topic character varying(255) NOT NULL,
    record_key character varying(255) NOT NULL,
    payload text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    published_at timestamp(6) with time zone,
    attempts integer NOT NULL DEFAULT 0,
    last_error text,
    CONSTRAINT event_outbox_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_event_outbox_unpublished ON event_outbox (id) WHERE published_at IS NULL;
CREATE INDEX idx_event_outbox_published_window ON event_outbox (topic, created_at)
    WHERE published_at IS NOT NULL;

-- Idempotency log for replica consumers, keyed by envelope eventId.
CREATE TABLE processed_events (
    event_id character varying(36) NOT NULL,
    owner character varying(64) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id)
);
CREATE INDEX idx_processed_events_owner_event ON processed_events (owner, event_id);

-- Read-only person identity replica fed by people-contact.events.v1. Contact points are
-- flattened to the HR shape (primary/secondary email, first two work phones).
CREATE TABLE ext_people_contact_person (
    person_id uuid NOT NULL,
    first_name character varying(255),
    last_name character varying(255),
    preferred_name character varying(255),
    primary_email character varying(255),
    secondary_email character varying(255),
    primary_phone character varying(255),
    secondary_phone character varying(255),
    person_created_at timestamp(6) with time zone,
    person_updated_at timestamp(6) with time zone,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_people_contact_person_pkey PRIMARY KEY (person_id)
);
CREATE INDEX idx_ext_person_last_name ON ext_people_contact_person (last_name);
CREATE INDEX idx_ext_person_primary_email ON ext_people_contact_person (primary_email);

-- Read-only user-person-link replica (username lookups for HR views).
CREATE TABLE ext_people_contact_user_link (
    link_id uuid NOT NULL,
    person_id uuid NOT NULL,
    username character varying(255) NOT NULL,
    status character varying(20) NOT NULL,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_people_contact_user_link_pkey PRIMARY KEY (link_id)
);
CREATE INDEX idx_ext_user_link_person ON ext_people_contact_user_link (person_id);
CREATE INDEX idx_ext_user_link_username ON ext_people_contact_user_link (username);
