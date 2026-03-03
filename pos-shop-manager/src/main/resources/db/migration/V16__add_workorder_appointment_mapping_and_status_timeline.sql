-- Create work order to appointment mapping table
CREATE TABLE work_order_appointment_mapping (
    work_order_id UUID PRIMARY KEY,
    appointment_id UUID NOT NULL REFERENCES appointment(appointment_id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    status VARCHAR(50)
);

CREATE INDEX idx_work_order_appointment_mapping_appointment_id
    ON work_order_appointment_mapping (appointment_id);

-- Create appointment status timeline table
CREATE TABLE appointment_status_timeline (
    id BIGSERIAL PRIMARY KEY,
    appointment_id UUID NOT NULL REFERENCES appointment(appointment_id),
    status VARCHAR(50) NOT NULL,
    change_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    source_event_id UUID NOT NULL
);

CREATE UNIQUE INDEX idx_appointment_status_timeline_source_event_id
    ON appointment_status_timeline (appointment_id, source_event_id);

CREATE INDEX idx_appointment_status_timeline_appointment_id
    ON appointment_status_timeline (appointment_id);

-- Add reopen_flag to appointment table
ALTER TABLE appointment ADD COLUMN IF NOT EXISTS reopen_flag BOOLEAN NOT NULL DEFAULT FALSE;
