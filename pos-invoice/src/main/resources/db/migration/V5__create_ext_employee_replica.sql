-- ADR-0044 §6 / #877: read-only employment replica fed by people.events.v1
-- (people.employee.updated). Serves employee-number -> active person resolution for
-- manager-approval elevation, replacing the retired synchronous people lookup.
CREATE TABLE ext_people_employee (
    employee_id uuid NOT NULL,
    person_id uuid NOT NULL,
    employee_number character varying(64),
    status character varying(20) NOT NULL,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_people_employee_pkey PRIMARY KEY (employee_id)
);
CREATE INDEX idx_inv_ext_employee_number ON ext_people_employee (employee_number);

-- Idempotency log for replica consumers (ADR-0044 §4).
CREATE TABLE processed_events (
    event_id character varying(36) NOT NULL,
    owner character varying(64) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id)
);
CREATE INDEX idx_processed_events_owner_event ON processed_events (owner, event_id);
