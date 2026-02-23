CREATE TABLE bays (
    id UUID PRIMARY KEY,
    location_id UUID NOT NULL,
    name VARCHAR2(255) NOT NULL,
    normalized_name VARCHAR2(255) NOT NULL,
    bay_type VARCHAR2(50) NOT NULL,
    status VARCHAR2(50) NOT NULL DEFAULT 'ACTIVE',
    max_concurrent_vehicles INTEGER NOT NULL,
    service_capability_ids TEXT,
    skill_requirement_ids TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_modified_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_bays_location_normalized_name UNIQUE (location_id, normalized_name)
);

CREATE INDEX idx_bays_location_id ON bays(location_id);
CREATE INDEX idx_bays_location_status ON bays(location_id, status);
CREATE INDEX idx_bays_location_bay_type ON bays(location_id, bay_type);
