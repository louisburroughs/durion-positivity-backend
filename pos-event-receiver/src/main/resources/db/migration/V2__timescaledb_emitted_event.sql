-- TimescaleDB hypertable, compression and hourly continuous aggregate for emitted_event.
-- Hand-carried verbatim from the retired V2/V3 (flattened 2026-09-09); kept separate from the
-- baseline because it needs the timescaledb extension (Compose image timescale/timescaledb).
-- executeInTransaction=false (see .conf): a continuous aggregate cannot be created in a transaction.
CREATE EXTENSION IF NOT EXISTS timescaledb;
SELECT create_hypertable('emitted_event', by_range('published_at'), migrate_data => true);
SELECT set_chunk_time_interval('emitted_event', INTERVAL '1 day');
ALTER TABLE emitted_event SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'id',
    timescaledb.compress_orderby = 'published_at DESC'
);
SELECT add_compression_policy('emitted_event', INTERVAL '7 days');
CREATE MATERIALIZED VIEW emitted_event_hourly WITH (timescaledb.continuous) AS
SELECT time_bucket('1 hour', published_at) AS bucket,
  id AS event_type,
  COUNT(*) AS event_count,
  AVG(elapsed_ms) AS avg_elapsed_ms,
  PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY elapsed_ms) AS p95_elapsed_ms,
  PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY elapsed_ms) AS p99_elapsed_ms
FROM emitted_event
GROUP BY bucket, id;
SELECT add_continuous_aggregate_policy('emitted_event_hourly',
    start_offset => INTERVAL '3 hours', end_offset => INTERVAL '1 hour', schedule_interval => INTERVAL '1 hour');
