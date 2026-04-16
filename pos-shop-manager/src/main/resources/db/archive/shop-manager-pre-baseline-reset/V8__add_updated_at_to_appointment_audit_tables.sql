ALTER TABLE appointment_service_request
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE appointment_service_request
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE appointment_service_request
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE appointment_audit
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE appointment_audit
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE appointment_audit
    ALTER COLUMN updated_at SET NOT NULL;
