-- ADR-0044 §6 (#892): read-only location replica fed by location.location.updated/deleted
-- facts on location.events.v1. Replaces the synchronous LocationReferenceClient lookups
-- (existence/active validation and display names).
CREATE TABLE ext_location (
    location_id uuid NOT NULL,
    name varchar(255),
    status varchar(64),
    active boolean NOT NULL DEFAULT false,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_location_pkey PRIMARY KEY (location_id)
);
