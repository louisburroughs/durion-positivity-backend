-- Add status column to user_person_links table (idempotent)
-- Use IF NOT EXISTS to avoid errors if the column was already created
-- by Hibernate's ddl-auto=update or another mechanism.
ALTER TABLE user_person_links
    ADD COLUMN IF NOT EXISTS status VARCHAR(20);

-- Ensure existing rows have a value; default to 'ACTIVE' where NULL
UPDATE user_person_links
SET status = 'ACTIVE'
WHERE status IS NULL;

-- Enforce default and NOT NULL constraint on status
ALTER TABLE user_person_links
    ALTER COLUMN status SET DEFAULT 'ACTIVE',
    ALTER COLUMN status SET NOT NULL;

-- Create partial unique index to ensure one ACTIVE user per person
-- This allows multiple INACTIVE links but only one ACTIVE link per person
-- Use IF NOT EXISTS to avoid errors if the index is already present.
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_person_links_person_active 
    ON user_person_links(person_id) 
    WHERE status = 'ACTIVE';
