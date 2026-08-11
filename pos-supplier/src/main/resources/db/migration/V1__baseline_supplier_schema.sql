-- ADR-0044 §4: idempotency log for command consumption, keyed by the command envelope's
-- eventId. pos-supplier consumes supplier.commands.v1 (supplier.order.requested,
-- supplier.workorderauth.requested — ADR-0049 §3) and workorder.events.v1
-- (workorder.completed, CAP-323); replaying a command without this guard would mint a
-- duplicate transmission intent (ADR-0052 §2). Column shape matches the platform-wide
-- processed_events convention (e.g. pos-people-contact V3, pos-catalog V5).
CREATE TABLE processed_events (
    event_id character varying(36) NOT NULL,
    owner character varying(64) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id)
);

CREATE INDEX idx_processed_events_owner_event ON processed_events (owner, event_id);
