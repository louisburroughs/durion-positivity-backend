-- Create event_outbox table for transactional outbox pattern
-- Ensures at-least-once event delivery with ACID guarantees

CREATE TABLE event_outbox (
    outbox_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    last_attempt_at TIMESTAMP
);

-- Index for efficient polling of pending events (most common query)
CREATE INDEX idx_outbox_status_created ON event_outbox(status, created_at);

-- Index for querying events by aggregate (for monitoring/debugging)
CREATE INDEX idx_outbox_aggregate ON event_outbox(aggregate_type, aggregate_id);

-- Index for finding events by type (for operational queries)
CREATE INDEX idx_outbox_event_type ON event_outbox(event_type);

-- Add comments for documentation
COMMENT ON TABLE event_outbox IS 'Transactional outbox for reliable event delivery with at-least-once semantics';
COMMENT ON COLUMN event_outbox.event_id IS 'Idempotency key for duplicate detection';
COMMENT ON COLUMN event_outbox.aggregate_type IS 'Business entity type (e.g., APPayment, JournalEntry)';
COMMENT ON COLUMN event_outbox.aggregate_id IS 'Business entity identifier';
COMMENT ON COLUMN event_outbox.event_type IS 'Fully qualified event class name';
COMMENT ON COLUMN event_outbox.payload IS 'JSON-serialized event data';
COMMENT ON COLUMN event_outbox.status IS 'Processing status: PENDING, PUBLISHED, or FAILED';
COMMENT ON COLUMN event_outbox.retry_count IS 'Number of publication attempts';
COMMENT ON COLUMN event_outbox.last_error IS 'Error message from last failed attempt';
