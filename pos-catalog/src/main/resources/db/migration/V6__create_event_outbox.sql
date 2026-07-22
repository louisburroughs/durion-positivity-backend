-- ADR-0044 §4 (#924): transactional outbox for catalog domain events (catalog.events.v1).
-- Rows are written in the same transaction as the product mutation and drained to Kafka by a
-- background publisher (at-least-once delivery). H2(MODE=PostgreSQL)-compatible: plain indexes
-- only (no partial-index predicates), so the dev/@DataJpaTest slices replay this migration too.
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

-- Drain scans unpublished rows in id order (UUIDv7 ids are time-ordered).
CREATE INDEX idx_event_outbox_unpublished ON event_outbox (published_at, id);

-- Manifest computation scans published rows of a topic by created_at window (ADR-0044 §4).
CREATE INDEX idx_event_outbox_published_window ON event_outbox (topic, published_at, created_at);
