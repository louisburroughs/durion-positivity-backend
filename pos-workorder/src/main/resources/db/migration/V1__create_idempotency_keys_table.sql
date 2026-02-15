-- Create idempotency_keys table for preventing duplicate workorder creation
-- Stores idempotency keys with 24-hour expiration for safe retry semantics

CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY,
    key_value VARCHAR(255) NOT NULL UNIQUE,
    workorder_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL
);

-- Index for efficient key lookup (most common query)
CREATE UNIQUE INDEX idx_key_value ON idempotency_keys(key_value);

-- Index for efficient expiration cleanup
CREATE INDEX idx_expires_at ON idempotency_keys(expires_at);

-- Add comments for documentation
COMMENT ON TABLE idempotency_keys IS 'Tracks idempotency keys for workorder creation to prevent duplicate processing';
COMMENT ON COLUMN idempotency_keys.key_value IS 'Client-provided idempotency key (UUID or other unique string)';
COMMENT ON COLUMN idempotency_keys.workorder_id IS 'ID of the workorder created with this key';
COMMENT ON COLUMN idempotency_keys.created_at IS 'When the key was first registered';
COMMENT ON COLUMN idempotency_keys.expires_at IS 'When the key expires (24 hours from creation)';
