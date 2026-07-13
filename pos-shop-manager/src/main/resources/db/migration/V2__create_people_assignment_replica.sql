-- ADR-0044 §6 / #877: read-only staffing-assignment replica fed by people.events.v1
-- (people.staffing-assignment.updated). Drives mechanic schedule/availability views;
-- pos-people owns the facts.
CREATE TABLE ext_people_staffing_assignment (
    assignment_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    person_id uuid NOT NULL,
    location_id uuid NOT NULL,
    role character varying(100),
    is_primary boolean NOT NULL,
    status character varying(20) NOT NULL,
    effective_from date,
    effective_to date,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_people_staffing_assignment_pkey PRIMARY KEY (assignment_id)
);
CREATE INDEX idx_sm_ext_assignment_person ON ext_people_staffing_assignment (person_id, status);
CREATE INDEX idx_sm_ext_assignment_location ON ext_people_staffing_assignment (location_id, status);

-- Idempotency log for replica consumers (ADR-0044 §4).
CREATE TABLE processed_events (
    event_id character varying(36) NOT NULL,
    owner character varying(64) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id)
);
CREATE INDEX idx_processed_events_owner_event ON processed_events (owner, event_id);
