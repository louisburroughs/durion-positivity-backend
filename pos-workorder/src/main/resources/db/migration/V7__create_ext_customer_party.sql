-- ADR-0044 §6 (#891): read-only customer-party replica fed by customer.events.v1,
-- carrying the owner-computed requirements-met verdict. Replaces the synchronous
-- CustomerValidationClient. pos-customer owns the data; only the Kafka consumer writes here.
CREATE TABLE ext_customer_party (
    party_id uuid NOT NULL,
    party_type character varying(32) NOT NULL,
    display_name character varying(255),
    status character varying(32) NOT NULL,
    requirements_met boolean NOT NULL DEFAULT false,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_customer_party_pkey PRIMARY KEY (party_id)
);
