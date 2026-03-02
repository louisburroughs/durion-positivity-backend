ALTER TABLE estimate ADD COLUMN appointment_id UUID;
CREATE INDEX idx_estimate_appointment_id ON estimate(appointment_id);