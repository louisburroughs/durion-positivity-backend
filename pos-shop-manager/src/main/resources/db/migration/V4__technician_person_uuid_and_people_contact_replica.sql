-- #885: technician.person_id predates UUIDv7 person ids (bigint), so no stored value ever
-- addressed a real person and no backfill is possible. Retype in place, dropping the dead
-- legacy values, and add the people-contact person replica the read path resolves against.
ALTER TABLE technician DROP COLUMN person_id;
ALTER TABLE technician ADD COLUMN person_id uuid;
-- Lookup path for GET .../technicians/{personId}/person (findFirstByShopIdAndPersonId).
CREATE INDEX idx_sm_technician_shop_person ON technician (shop_id, person_id);

-- ADR-0044 §6 / #877 pattern: read-only person identity replica fed by people-contact.events.v1
-- (names + contact points). pos-people-contact publishes the facts; nothing in this module may
-- write this table except the event consumer. processed_events already exists (V2).
CREATE TABLE ext_people_contact_person (
    person_id uuid NOT NULL,
    first_name character varying(255),
    last_name character varying(255),
    primary_email character varying(255),
    contact_points text,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_people_contact_person_pkey PRIMARY KEY (person_id)
);
CREATE INDEX idx_sm_ext_person_last_name ON ext_people_contact_person (last_name);
