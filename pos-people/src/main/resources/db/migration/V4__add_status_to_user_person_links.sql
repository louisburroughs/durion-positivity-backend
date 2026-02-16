-- Add status column to user_person_links table
ALTER TABLE user_person_links
ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

-- Create partial unique index to ensure one ACTIVE user per person
-- This allows multiple INACTIVE links but only one ACTIVE link per person
CREATE UNIQUE INDEX idx_user_person_links_person_active 
ON user_person_links(person_id) 
WHERE status = 'ACTIVE';
