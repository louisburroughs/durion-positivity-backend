ALTER TABLE IF EXISTS workorder ADD COLUMN IF NOT EXISTS location_id UUID;
ALTER TABLE IF EXISTS workorder ADD COLUMN IF NOT EXISTS resource_id UUID;
ALTER TABLE IF EXISTS workorder ADD COLUMN IF NOT EXISTS mechanic_ids TEXT;
-- mechanic_ids stored as JSON array e.g. ["uuid1","uuid2"]
