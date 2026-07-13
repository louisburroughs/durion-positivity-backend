-- ADR-0044 §6 / #877: read-only person identity replica fed by people-contact.events.v1.
-- pos-people-contact owns these facts; nothing in this module may write the table except
-- the event consumer. contact_points holds the full typed list (JSON) because CRM flows
-- surface arbitrary contact types; primary_email is flattened for search.
CREATE TABLE ext_people_contact_person (
    person_id uuid NOT NULL,
    first_name character varying(255),
    last_name character varying(255),
    preferred_name character varying(255),
    primary_email character varying(255),
    contact_points text,
    person_created_at timestamp(6) with time zone,
    person_updated_at timestamp(6) with time zone,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_people_contact_person_pkey PRIMARY KEY (person_id)
);
CREATE INDEX idx_cust_ext_person_primary_email ON ext_people_contact_person (primary_email);
CREATE INDEX idx_cust_ext_person_last_name ON ext_people_contact_person (last_name);
