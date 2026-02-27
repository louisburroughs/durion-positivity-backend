CREATE TABLE appointment_audit (
    audit_id UUID PRIMARY KEY,
    appointment_id UUID NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    previous_start_at TIMESTAMPTZ,
    previous_end_at TIMESTAMPTZ,
    new_start_at TIMESTAMPTZ,
    new_end_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_appointment_id ON appointment_audit (appointment_id);
CREATE INDEX idx_audit_created_at ON appointment_audit (appointment_id, created_at DESC);