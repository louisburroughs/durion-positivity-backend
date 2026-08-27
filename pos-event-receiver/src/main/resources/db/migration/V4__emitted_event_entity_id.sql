-- Adds an optional entity id to emitted_event so callers can query event history for one
-- entity (issue #1521). Telemetry is opt-in: an @EmitEvent caller that does not supply an
-- entityId leaves this column null, and such events are simply never returned by the
-- entity-indexed query endpoint.
--
-- Nullable, no default: safe to add on a compressed TimescaleDB hypertable (V2) without
-- rewriting existing chunks.
ALTER TABLE emitted_event ADD COLUMN entity_id VARCHAR(64);

-- Partial index: entity_id is sparse (most events never set it), so indexing only the
-- non-null rows keeps the index small while still serving the entity + published_at DESC
-- lookup the query endpoint needs.
CREATE INDEX idx_emitted_event_entity_time
    ON emitted_event (entity_id, published_at DESC)
    WHERE entity_id IS NOT NULL;
