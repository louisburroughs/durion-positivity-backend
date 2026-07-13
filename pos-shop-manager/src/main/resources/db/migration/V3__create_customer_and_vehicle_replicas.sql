-- ADR-0044 §6 (#891): read-only replicas replacing the synchronous CRM clients.
-- pos-customer owns ext_customer_party (fed by customer.events.v1);
-- pos-vehicle-inventory owns ext_vehicle (fed by vehicle.events.v1, the Phase 2
-- #843 deferral for this module). Only the Kafka consumers write here.
CREATE TABLE ext_customer_party (
    party_id uuid NOT NULL,
    party_type character varying(32) NOT NULL,
    customer_number character varying(64),
    display_name character varying(255),
    status character varying(32) NOT NULL,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_customer_party_pkey PRIMARY KEY (party_id)
);

CREATE TABLE ext_vehicle (
    vehicle_id uuid NOT NULL,
    account_id uuid NOT NULL,
    vin character varying(17) NOT NULL,
    unit_number character varying(255),
    description text,
    license_plate character varying(50),
    model_year integer,
    make character varying(255),
    model character varying(255),
    active boolean NOT NULL,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_vehicle_pkey PRIMARY KEY (vehicle_id)
);

-- Ownership validation looks vehicles up by owning party.
CREATE INDEX idx_ext_vehicle_account ON ext_vehicle (account_id);
