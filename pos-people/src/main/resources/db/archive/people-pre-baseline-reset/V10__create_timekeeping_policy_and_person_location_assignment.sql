-- Add missing tables to align Flyway schema with current JPA entity mappings.

CREATE TABLE IF NOT EXISTS timekeeping_policy (
    timekeeping_policy_id UUID PRIMARY KEY,
    scope_type VARCHAR(16) NOT NULL,
    scope_id UUID,
    job_time_discrepancy_threshold_minutes INTEGER NOT NULL,
    effective_start_at TIMESTAMP WITH TIME ZONE,
    effective_end_at TIMESTAMP WITH TIME ZONE,
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_timekeeping_policy_scope
    ON timekeeping_policy(scope_type, scope_id);

CREATE INDEX IF NOT EXISTS idx_timekeeping_policy_scope_effective_updated
    ON timekeeping_policy(scope_type, scope_id, effective_start_at, effective_end_at, updated_at);

CREATE TABLE IF NOT EXISTS person_location_assignment (
    id UUID PRIMARY KEY,
    person_id UUID NOT NULL,
    location_id UUID NOT NULL,
    role VARCHAR(255) NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    effective_from DATE NOT NULL,
    effective_to DATE,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(255)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_person_location_assignment_unique
    ON person_location_assignment(person_id, location_id, role, effective_from);

CREATE INDEX IF NOT EXISTS idx_person_location_assignment_person_id
    ON person_location_assignment(person_id);

CREATE INDEX IF NOT EXISTS idx_person_location_assignment_location_id
    ON person_location_assignment(location_id);
