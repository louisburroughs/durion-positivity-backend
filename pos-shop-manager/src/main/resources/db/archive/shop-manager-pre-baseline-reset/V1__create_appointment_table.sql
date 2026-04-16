CREATE TABLE appointment (
    appointment_id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    location_id UUID NOT NULL,
    resource_id VARCHAR(128),
    resource_type VARCHAR(64),
    crm_customer_id UUID NOT NULL,
    crm_vehicle_id UUID NOT NULL,
    customer_snapshot TEXT,
    vehicle_snapshot TEXT,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    workorder_link_ref VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_appointment_location_start ON appointment (location_id, start_at);
CREATE INDEX idx_appointment_status ON appointment (status);
