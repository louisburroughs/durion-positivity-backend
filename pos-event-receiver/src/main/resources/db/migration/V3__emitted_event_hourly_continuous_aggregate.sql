CREATE MATERIALIZED VIEW emitted_event_hourly WITH (timescaledb.continuous) AS
SELECT time_bucket('1 hour', published_at) AS bucket,
  id AS event_type,
  COUNT(*) AS event_count,
  AVG(elapsed_ms) AS avg_elapsed_ms,
  PERCENTILE_CONT(0.95) WITHIN GROUP (
    ORDER BY elapsed_ms
  ) AS p95_elapsed_ms,
  PERCENTILE_CONT(0.99) WITHIN GROUP (
    ORDER BY elapsed_ms
  ) AS p99_elapsed_ms
FROM emitted_event
GROUP BY bucket,
  id;
SELECT add_continuous_aggregate_policy(
    'emitted_event_hourly',
    start_offset => INTERVAL '3 hours',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour'
  );