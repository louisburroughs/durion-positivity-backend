ALTER TABLE IF EXISTS location
    ADD COLUMN IF NOT EXISTS normalized_name VARCHAR(255);

ALTER TABLE IF EXISTS location
    ADD COLUMN IF NOT EXISTS version BIGINT;

ALTER TABLE IF EXISTS location
    ADD COLUMN IF NOT EXISTS status VARCHAR(50);

ALTER TABLE IF EXISTS location
    ADD COLUMN IF NOT EXISTS timezone VARCHAR(100);

ALTER TABLE IF EXISTS location
    ADD COLUMN IF NOT EXISTS operating_hours TEXT;

ALTER TABLE IF EXISTS location
    ADD COLUMN IF NOT EXISTS holiday_closures TEXT;

ALTER TABLE IF EXISTS location
    ADD COLUMN IF NOT EXISTS check_in_buffer_minutes INTEGER;

ALTER TABLE IF EXISTS location
    ADD COLUMN IF NOT EXISTS cleanup_buffer_minutes INTEGER;

CREATE INDEX IF NOT EXISTS idx_location_status ON location(status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_location_normalized_name ON location(normalized_name);
