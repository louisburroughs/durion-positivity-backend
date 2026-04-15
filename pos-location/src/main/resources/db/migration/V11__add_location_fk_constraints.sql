-- Add FK constraints to location after storage_location (V5) and location_type (V10) exist.
-- Uses DO-block guards for idempotency on environments that may already have constraints.
DO $$ BEGIN IF NOT EXISTS (
  SELECT 1
  FROM pg_constraint
  WHERE conname = 'fk_location_staging'
) THEN
ALTER TABLE location
ADD CONSTRAINT fk_location_staging FOREIGN KEY (default_staging_location_id) REFERENCES storage_location(id);
END IF;
IF NOT EXISTS (
  SELECT 1
  FROM pg_constraint
  WHERE conname = 'fk_location_quarantine'
) THEN
ALTER TABLE location
ADD CONSTRAINT fk_location_quarantine FOREIGN KEY (default_quarantine_location_id) REFERENCES storage_location(id);
END IF;
IF NOT EXISTS (
  SELECT 1
  FROM pg_constraint
  WHERE conname = 'fk_location_type'
) THEN
ALTER TABLE location
ADD CONSTRAINT fk_location_type FOREIGN KEY (location_type_id) REFERENCES location_type(id);
END IF;
END $$;
