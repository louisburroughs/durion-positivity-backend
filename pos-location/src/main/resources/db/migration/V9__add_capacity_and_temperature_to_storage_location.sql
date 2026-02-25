-- Issue CAP-214 #39: add optional capacity and temperature metadata for storage locations.
ALTER TABLE storage_location
    ADD COLUMN IF NOT EXISTS capacity TEXT;

ALTER TABLE storage_location
    ADD COLUMN IF NOT EXISTS temperature TEXT;
