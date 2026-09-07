-- ADR-0061 §1 (#1867): read model of pos-people's employee_location_assignment, fed by
-- people.events.v1 (people.staffing-assignment.updated). pos-people owns the facts; the
-- consumer (PeopleEventsListener) is the only writer.
--
-- location_id is the ASSIGNED node verbatim (shop, District, Region or HQ). The hierarchy is
-- evaluated at check time in the owning service (ADR-0061 §2), never expanded here.
--
-- Ended assignments are kept (status = 'ENDED'), not deleted: effective dating drives the
-- token exp clamp (ADR-0061 §4, #1873).
CREATE TABLE ext_people_staffing_assignment (
    assignment_id uuid NOT NULL,
    person_id uuid NOT NULL,
    location_id uuid NOT NULL,
    is_primary boolean NOT NULL DEFAULT FALSE,
    status character varying(20) NOT NULL,
    effective_from date,
    effective_to date,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_people_staffing_assignment_pkey PRIMARY KEY (assignment_id)
);
CREATE INDEX idx_sec_ext_staffing_person ON ext_people_staffing_assignment (person_id);
