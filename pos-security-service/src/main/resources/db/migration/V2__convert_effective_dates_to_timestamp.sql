-- Convert effective_start_date and effective_end_date from DATE to TIMESTAMP WITH TIME ZONE
-- to support LocalDateTime in the entity model

-- Drop the existing index on effective dates
DROP INDEX IF EXISTS idx_role_assignments_effective_dates;

-- Alter the columns to TIMESTAMP WITH TIME ZONE
-- For existing DATE values, they will be converted to timestamp at midnight UTC
ALTER TABLE role_assignments
    ALTER COLUMN effective_start_date TYPE TIMESTAMP WITH TIME ZONE;

ALTER TABLE role_assignments
    ALTER COLUMN effective_end_date TYPE TIMESTAMP WITH TIME ZONE;

-- Recreate the index on the new timestamp columns
CREATE INDEX idx_role_assignments_effective_dates
    ON role_assignments(effective_start_date, effective_end_date);
