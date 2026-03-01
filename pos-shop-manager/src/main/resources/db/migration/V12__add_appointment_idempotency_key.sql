ALTER TABLE appointment
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_appointment_idempotency_key
    ON appointment (idempotency_key);
