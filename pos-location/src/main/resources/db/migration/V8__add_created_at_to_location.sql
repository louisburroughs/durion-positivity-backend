-- Issue CAP-214 #39: Standardize location audit timestamps.
ALTER TABLE IF EXISTS location
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;

UPDATE location
SET created_at = COALESCE(created_at, updated_at, NOW())
WHERE created_at IS NULL;

ALTER TABLE IF EXISTS location
    ALTER COLUMN created_at SET NOT NULL;
