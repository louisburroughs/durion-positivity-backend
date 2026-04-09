CREATE TABLE IF NOT EXISTS event_type (
  id UUID PRIMARY KEY,
  type_code VARCHAR(255) NOT NULL,
  description VARCHAR(255) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  api_version VARCHAR(255) NOT NULL DEFAULT '1',
  p50_micros BIGINT NOT NULL DEFAULT 10000000,
  p95_micros BIGINT NOT NULL DEFAULT 10000000,
  p99_micros BIGINT NOT NULL DEFAULT 10000000,
  CONSTRAINT uk_event_type_type_code UNIQUE (type_code)
);
CREATE TABLE IF NOT EXISTS preregistered_event (id VARCHAR(255) PRIMARY KEY);
CREATE TABLE IF NOT EXISTS emitted_event (
  event_id UUID PRIMARY KEY,
  id VARCHAR(255),
  api_version VARCHAR(255),
  timestamp BIGINT,
  elapsed_ms BIGINT,
  published_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_emitted_event_id ON emitted_event (id);