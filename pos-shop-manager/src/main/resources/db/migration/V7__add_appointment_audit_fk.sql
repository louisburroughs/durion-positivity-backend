ALTER TABLE appointment_audit
    ADD CONSTRAINT fk_audit_appointment
    FOREIGN KEY (appointment_id) REFERENCES appointment(appointment_id) ON DELETE CASCADE;

CREATE INDEX idx_appointment_workorder_link_ref ON appointment (workorder_link_ref);
