-- Story #1145: pos-marketing baseline.
-- The module owns campaign definition, templates, audience binding, send orchestration, and
-- campaign analytics. It has no compile-time dependency on pos-customer and no cross-service
-- foreign keys: CRM data reaches this schema only through gateway REST and customer.events.v1
-- (ADR-0044). This baseline therefore contains only the event rails; domain tables arrive in
-- V2 onward.

-- ADR-0044 §4: transactional outbox. A fact exists if and only if the business state change
-- committed, and OutboxPublisher drains it to marketing.events.v1 at least once.
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

-- Drain scans: unpublished rows in id order (UUIDv7 ids are time-ordered). Plain composite
-- index instead of a Postgres partial index, for H2 (dev) compatibility.
CREATE INDEX idx_event_outbox_unpublished ON event_outbox (published_at, id);

-- ADR-0044 §4: consumer-side idempotency log, one row per applied envelope eventId, written
-- in the same transaction as the state it drives. owner = the producing domain, so one
-- producer's replay window can never be confused with another's.
CREATE TABLE processed_events (
    event_id character varying(36) NOT NULL,
    owner character varying(64) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id)
);

CREATE INDEX idx_processed_events_owner_event ON processed_events (owner, event_id);
