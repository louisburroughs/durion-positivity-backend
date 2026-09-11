-- TimescaleDB hypertable, compression and hourly continuous aggregate for emitted_event.
-- Hand-carried verbatim from the retired V2/V3 (flattened 2026-09-09); kept separate from the
-- baseline because it needs the timescaledb extension (Compose image timescale/timescaledb).
-- executeInTransaction=false (see .conf): a continuous aggregate cannot be created in a transaction.
--
-- Per-tenant observability (ADR-0062 plan WS6, decided 2026-09-10): the hourly aggregate is grouped
-- by tenant_id as well as event type, so a tenant's statistics are its own rows and the global view
-- is the sum across tenants. emitted_event has no row-level security (V1_1), so the aggregate has
-- none either; tenant_id is a data column that every reader names (EmittedEventHourlyRepository).
-- Edited in place (docs/TENANCY_SCHEMA.md): a database that already carries the tenant-less
-- aggregate is reset, not migrated.
CREATE EXTENSION IF NOT EXISTS timescaledb;
SELECT create_hypertable('emitted_event', by_range('published_at'), migrate_data => true);
SELECT set_chunk_time_interval('emitted_event', INTERVAL '1 day');
ALTER TABLE emitted_event SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'tenant_id, id',
    timescaledb.compress_orderby = 'published_at DESC'
);
SELECT add_compression_policy('emitted_event', INTERVAL '7 days');
CREATE MATERIALIZED VIEW emitted_event_hourly WITH (timescaledb.continuous) AS
SELECT time_bucket('1 hour', published_at) AS bucket,
  tenant_id,
  id AS event_type,
  COUNT(*) AS event_count,
  AVG(elapsed_ms) AS avg_elapsed_ms,
  PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY elapsed_ms) AS p95_elapsed_ms,
  PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY elapsed_ms) AS p99_elapsed_ms
FROM emitted_event
GROUP BY bucket, tenant_id, id;
COMMENT ON COLUMN emitted_event_hourly.tenant_id IS
    'Producing tenant (ADR-0062 plan WS6). Data column, not a policy: every reader names it, and the global view is the sum across tenants.';
SELECT add_continuous_aggregate_policy('emitted_event_hourly',
    start_offset => INTERVAL '3 hours', end_offset => INTERVAL '1 hour', schedule_interval => INTERVAL '1 hour');
