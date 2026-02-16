-- Add revoked_at timestamp to track when revocation was entered
-- This provides an immutable audit trail for when the revocation was set,
-- independent of the effective_end_date which may be in the past or future
ALTER TABLE role_assignments ADD COLUMN revoked_at TIMESTAMP WITH TIME ZONE;

-- Add index for querying revoked assignments
CREATE INDEX IF NOT EXISTS idx_role_assignments_revoked_at ON role_assignments(revoked_at);
