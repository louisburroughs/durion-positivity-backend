-- Issue CAP-214 #40: Add roster sync columns to location.
ALTER TABLE location
    ADD COLUMN IF NOT EXISTS hr_location_id VARCHAR(255);

ALTER TABLE location
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;
