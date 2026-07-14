-- ADR-0044 §6 (#891): read-only CRM customer-standing replica fed by
-- customer.events.v1 (customer.person-identity.updated). Replaces the synchronous
-- CustomerRegistrationClient person search for self-registration conflict signals.
-- pos-customer owns the data; only the Kafka consumer writes here.
CREATE TABLE ext_customer_person_identity (
    person_id uuid NOT NULL,
    person_party_id uuid,
    individual_customer boolean NOT NULL DEFAULT false,
    commercial_contact boolean NOT NULL DEFAULT false,
    commercial_account_count integer NOT NULL DEFAULT 0,
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_customer_person_identity_pkey PRIMARY KEY (person_id)
);
