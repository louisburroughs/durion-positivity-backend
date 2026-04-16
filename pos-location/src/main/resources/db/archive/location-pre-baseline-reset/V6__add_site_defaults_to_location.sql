-- Issue CAP-214 #38: Site default storage location persistence.
ALTER TABLE location
    ADD COLUMN IF NOT EXISTS default_staging_location_id UUID;

ALTER TABLE location
    ADD COLUMN IF NOT EXISTS default_quarantine_location_id UUID;
