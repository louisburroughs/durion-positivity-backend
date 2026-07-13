-- ADR-0044 §4 (#875): idempotency log for command consumption, keyed by the command
-- envelope's eventId. Person upserts are last-writer-wins, so replaying an old command
-- after a newer write would regress identity attributes without this guard.
CREATE TABLE processed_events (
    event_id character varying(36) NOT NULL,
    owner character varying(64) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id)
);

CREATE INDEX idx_processed_events_owner_event ON processed_events (owner, event_id);
