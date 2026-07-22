-- ADR-0044 §6 (#892): read-only location replica fed by location.location.updated/deleted
-- facts on location.events.v1. Replaces the synchronous LocationServiceClient tax-address
-- lookup; carries the tax-jurisdiction address (the ADR-0044 reversal of the old
-- "never replicate address data" rule). Tax flows still run ADR-0021 validation on this data.
CREATE TABLE ext_location (
    location_id uuid NOT NULL,
    name varchar(255),
    active boolean NOT NULL DEFAULT false,
    address_line1 varchar(255),
    address_line2 varchar(255),
    city varchar(255),
    region varchar(64),
    postal_code varchar(32),
    country varchar(8),
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_location_pkey PRIMARY KEY (location_id)
);
