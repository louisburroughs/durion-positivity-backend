ALTER TABLE person
    ADD COLUMN IF NOT EXISTS legal_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS preferred_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS employee_number VARCHAR(255),
    ADD COLUMN IF NOT EXISTS status VARCHAR(50),
    ADD COLUMN IF NOT EXISTS hire_date DATE,
    ADD COLUMN IF NOT EXISTS termination_date DATE,
    ADD COLUMN IF NOT EXISTS contact_info_json TEXT,
    ADD COLUMN IF NOT EXISTS status_effective_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS idx_person_employee_number_unique
    ON person (LOWER(employee_number))
    WHERE employee_number IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_person_primary_email_unique
    ON person (LOWER(primary_email))
    WHERE primary_email IS NOT NULL;

CREATE TABLE IF NOT EXISTS employee_offboarding_retry_queue (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL,
    assignment_policy VARCHAR(64) NOT NULL,
    disable_reason TEXT,
    actor_id VARCHAR(255) NOT NULL,
    failure_reason TEXT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_offboarding_retry_employee FOREIGN KEY (employee_id) REFERENCES person(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_employee_offboarding_retry_next_attempt
    ON employee_offboarding_retry_queue(next_attempt_at);
