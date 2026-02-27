CREATE TABLE appointment_service_request (
    service_request_id UUID PRIMARY KEY,
    appointment_id UUID NOT NULL,
    service_entity_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_appointment_service_request_appointment
        FOREIGN KEY (appointment_id) REFERENCES appointment (appointment_id)
);

CREATE INDEX idx_appointment_service_request_appointment_id
    ON appointment_service_request (appointment_id);

CREATE INDEX idx_appointment_service_request_service_entity_id
    ON appointment_service_request (service_entity_id);
