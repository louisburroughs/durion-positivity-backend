-- Story D1 (odoo parity #1062): transactional outbox + consumer idempotency log (ADR-0044 §4).
CREATE TABLE IF NOT EXISTS event_outbox (
  id UUID PRIMARY KEY,
  topic VARCHAR(255) NOT NULL,
  record_key VARCHAR(255) NOT NULL,
  payload TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  published_at TIMESTAMP,
  attempts INTEGER NOT NULL DEFAULT 0,
  last_error TEXT
);
CREATE INDEX IF NOT EXISTS idx_event_outbox_unpublished ON event_outbox (published_at, id);

CREATE TABLE IF NOT EXISTS processed_events (
  event_id VARCHAR(36) PRIMARY KEY,
  owner VARCHAR(64) NOT NULL,
  processed_at TIMESTAMP NOT NULL
);
