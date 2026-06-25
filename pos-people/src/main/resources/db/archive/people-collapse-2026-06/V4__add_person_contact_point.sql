-- V4: Person contact points.
--
-- pos-people becomes the source of truth for person contact data (ADR-0015 I2),
-- consolidating the typed contact taxonomy previously held only in
-- pos-customer.contact_point. Additive: no existing data is altered.

CREATE TABLE person_contact_point (
    id            uuid NOT NULL,
    person_id     uuid NOT NULL,
    contact_type  character varying(20) NOT NULL,
    value         character varying(255) NOT NULL,
    is_primary    boolean NOT NULL DEFAULT false,
    created_at    timestamp(6) with time zone,
    updated_at    timestamp(6) with time zone,
    CONSTRAINT person_contact_point_pkey PRIMARY KEY (id),
    CONSTRAINT person_contact_point_type_check CHECK (
        contact_type IN ('EMAIL', 'PHONE_MOBILE', 'PHONE_HOME', 'PHONE_WORK')
    ),
    CONSTRAINT fk_person_contact_point_person FOREIGN KEY (person_id) REFERENCES person (id)
);

CREATE INDEX idx_person_contact_point_person ON person_contact_point (person_id);
CREATE INDEX idx_person_contact_point_type ON person_contact_point (contact_type);
