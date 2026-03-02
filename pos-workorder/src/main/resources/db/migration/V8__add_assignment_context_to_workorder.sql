ALTER TABLE workorder ADD COLUMN location_id UUID;
ALTER TABLE workorder ADD COLUMN resource_id UUID;
ALTER TABLE workorder ADD COLUMN mechanic_ids TEXT;
-- mechanic_ids stored as JSON array e.g. ["uuid1","uuid2"]
