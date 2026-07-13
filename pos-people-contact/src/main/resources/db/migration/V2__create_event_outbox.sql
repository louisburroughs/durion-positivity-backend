-- ADR-0044 §4: transactional outbox for domain events (people-contact.events.v1, issue #874).
-- Rows are written in the same transaction as the business state change and
-- drained to Kafka by a background publisher (at-least-once delivery).
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

-- Drain scans: unpublished rows in id order (UUIDv7 ids are time-ordered).
CREATE INDEX idx_event_outbox_unpublished ON event_outbox (id) WHERE published_at IS NULL;

-- Manifest computation scans published rows of a topic by created_at window (ADR-0044 §4).
CREATE INDEX idx_event_outbox_published_window ON event_outbox (topic, created_at)
    WHERE published_at IS NOT NULL;
