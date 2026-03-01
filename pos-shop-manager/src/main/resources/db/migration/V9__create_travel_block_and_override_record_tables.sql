CREATE TABLE IF NOT EXISTS travel_block (
    id UUID PRIMARY KEY,
    person_id VARCHAR(255) NOT NULL,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE travel_block
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;

UPDATE travel_block
SET created_at = NOW()
WHERE created_at IS NULL;

ALTER TABLE travel_block
    ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE travel_block
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE travel_block
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE travel_block
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_travel_block_person_window
    ON travel_block (person_id, start_time, end_time);

CREATE TABLE IF NOT EXISTS override_record (
    override_id UUID PRIMARY KEY,
    appointment_id UUID NOT NULL,
    overridden_by_user_id VARCHAR(255) NOT NULL,
    override_timestamp TIMESTAMPTZ NOT NULL,
    override_reason VARCHAR(2000) NOT NULL,
    conflict_details TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE override_record
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;

UPDATE override_record
SET created_at = NOW()
WHERE created_at IS NULL;

ALTER TABLE override_record
    ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE override_record
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE override_record
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE override_record
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_override_record_appointment_id
    ON override_record (appointment_id);
