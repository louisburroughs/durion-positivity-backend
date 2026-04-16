CREATE TABLE IF NOT EXISTS service_location_capabilities (
    id UUID PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_service_location_capabilities_active
    ON service_location_capabilities(active);
