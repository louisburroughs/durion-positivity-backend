-- Add missing location_type table to align Flyway schema with JPA entities.
-- Keep idempotent for environments where the table may already exist.

CREATE TABLE IF NOT EXISTS location_type (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_location_type_name ON location_type(name);
