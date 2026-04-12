CREATE EXTENSION IF NOT EXISTS timescaledb;
ALTER TABLE emitted_event DROP CONSTRAINT emitted_event_pkey;
ALTER TABLE emitted_event
ADD PRIMARY KEY (event_id, published_at);
SELECT create_hypertable(
    'emitted_event',
    by_range('published_at'),
    migrate_data => true
  );
SELECT set_chunk_time_interval('emitted_event', INTERVAL '1 day');
ALTER TABLE emitted_event
SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'id',
    timescaledb.compress_orderby = 'published_at DESC'
  );
SELECT add_compression_policy('emitted_event', INTERVAL '7 days');