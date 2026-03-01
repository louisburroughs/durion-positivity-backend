CREATE TABLE IF NOT EXISTS assignment (
    assignment_id UUID PRIMARY KEY,
    appointment_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    resource_id UUID,
    resource_type VARCHAR(50),
    is_override BOOLEAN NOT NULL DEFAULT FALSE,
    override_reason VARCHAR(2000),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE assignment
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;

UPDATE assignment
SET created_at = NOW()
WHERE created_at IS NULL;

ALTER TABLE assignment
    ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE assignment
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE assignment
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE assignment
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_assignment_appointment_id
    ON assignment (appointment_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_assignment_appointment_active
    ON assignment (appointment_id)
    WHERE status IN ('CONFIRMED', 'IN_PROGRESS');

CREATE TABLE IF NOT EXISTS assignment_mechanic (
    id UUID PRIMARY KEY,
    assignment_id UUID NOT NULL,
    mechanic_id UUID NOT NULL,
    role VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE assignment_mechanic
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;

UPDATE assignment_mechanic
SET created_at = NOW()
WHERE created_at IS NULL;

ALTER TABLE assignment_mechanic
    ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE assignment_mechanic
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE assignment_mechanic
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE assignment_mechanic
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_assignment_mechanic_assignment_id
    ON assignment_mechanic (assignment_id);

CREATE INDEX IF NOT EXISTS idx_assignment_mechanic_mechanic_id
    ON assignment_mechanic (mechanic_id);

CREATE TABLE IF NOT EXISTS hr_integration_log (
    event_id UUID PRIMARY KEY,
    person_id VARCHAR(255),
    event_type VARCHAR(255),
    received_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ,
    status VARCHAR(255),
    error_message VARCHAR(255),
    payload_hash VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE hr_integration_log
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;

UPDATE hr_integration_log
SET created_at = NOW()
WHERE created_at IS NULL;

ALTER TABLE hr_integration_log
    ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE hr_integration_log
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE hr_integration_log
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE hr_integration_log
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_hr_integration_log_person_id
    ON hr_integration_log (person_id);

CREATE TABLE IF NOT EXISTS mechanic (
    mechanic_id UUID PRIMARY KEY,
    person_id VARCHAR(255) NOT NULL,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    status VARCHAR(255),
    hire_date DATE,
    termination_date DATE,
    version INTEGER NOT NULL DEFAULT 0,
    last_synced_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE mechanic
    ALTER COLUMN person_id SET NOT NULL;

ALTER TABLE mechanic
    ALTER COLUMN version SET DEFAULT 0;

UPDATE mechanic
SET version = 0
WHERE version IS NULL;

ALTER TABLE mechanic
    ALTER COLUMN version SET NOT NULL;

ALTER TABLE mechanic
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;

UPDATE mechanic
SET created_at = NOW()
WHERE created_at IS NULL;

ALTER TABLE mechanic
    ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE mechanic
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE mechanic
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE mechanic
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_mechanic_person_id
    ON mechanic (person_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_mechanic_person_id
    ON mechanic (person_id);

CREATE INDEX IF NOT EXISTS idx_mechanic_status
    ON mechanic (status);

CREATE TABLE IF NOT EXISTS mechanic_audit_log (
    id UUID PRIMARY KEY,
    event_id UUID,
    person_id VARCHAR(255),
    event_type VARCHAR(255),
    before_state TEXT,
    after_state TEXT,
    applied_at TIMESTAMPTZ,
    changed_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE mechanic_audit_log
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;

UPDATE mechanic_audit_log
SET created_at = NOW()
WHERE created_at IS NULL;

ALTER TABLE mechanic_audit_log
    ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE mechanic_audit_log
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE mechanic_audit_log
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE mechanic_audit_log
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_mechanic_audit_log_event_id
    ON mechanic_audit_log (event_id);

CREATE TABLE IF NOT EXISTS mechanic_skill (
    id UUID PRIMARY KEY,
    mechanic_id UUID,
    skill_code VARCHAR(255),
    proficiency_level INTEGER,
    certified_date DATE,
    expiration_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE mechanic_skill
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;

UPDATE mechanic_skill
SET created_at = NOW()
WHERE created_at IS NULL;

ALTER TABLE mechanic_skill
    ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE mechanic_skill
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE mechanic_skill
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE mechanic_skill
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_mechanic_skill_mechanic_id
    ON mechanic_skill (mechanic_id);
