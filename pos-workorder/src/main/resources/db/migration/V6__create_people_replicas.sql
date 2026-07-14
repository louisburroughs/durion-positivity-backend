-- ADR-0044 §6 / #877: read-only replicas fed by people-contact.events.v1 (identity, links)
-- and people.events.v1 (staffing assignments). The owning modules publish the facts; nothing
-- in this module may write these tables except the event consumers.
CREATE TABLE ext_people_contact_person (
    person_id uuid NOT NULL,
    first_name character varying(255),
    last_name character varying(255),
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_people_contact_person_pkey PRIMARY KEY (person_id)
);

CREATE TABLE ext_people_contact_user_link (
    link_id uuid NOT NULL,
    person_id uuid NOT NULL,
    username character varying(255) NOT NULL,
    status character varying(20) NOT NULL,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_people_contact_user_link_pkey PRIMARY KEY (link_id)
);
CREATE INDEX idx_wo_ext_user_link_username ON ext_people_contact_user_link (username);

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
CREATE INDEX idx_wo_ext_assignment_location ON ext_people_staffing_assignment (location_id, status);
CREATE INDEX idx_wo_ext_assignment_person ON ext_people_staffing_assignment (person_id, status);

-- Idempotency log for replica consumers (ADR-0044 §4).
CREATE TABLE processed_events (
    event_id character varying(36) NOT NULL,
    owner character varying(64) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id)
);
CREATE INDEX idx_processed_events_owner_event ON processed_events (owner, event_id);
