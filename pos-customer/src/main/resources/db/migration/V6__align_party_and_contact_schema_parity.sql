-- Align inherited party/contact schema with current JPA mappings.

ALTER TABLE IF EXISTS commercial_party
    ADD COLUMN IF NOT EXISTS customer_id UUID,
    ADD COLUMN IF NOT EXISTS customer_number VARCHAR(255),
    ADD COLUMN IF NOT EXISTS phone_number VARCHAR(255),
    ADD COLUMN IF NOT EXISTS email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS status VARCHAR(64),
    ADD COLUMN IF NOT EXISTS tier VARCHAR(64),
    ADD COLUMN IF NOT EXISTS tier_assigned_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS tier_assigned_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS tier_manual_override BOOLEAN,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS display_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS tax_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS billing_terms_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS primary_address VARCHAR(255);

ALTER TABLE IF EXISTS person_party
    ADD COLUMN IF NOT EXISTS customer_id UUID,
    ADD COLUMN IF NOT EXISTS customer_number VARCHAR(255),
    ADD COLUMN IF NOT EXISTS phone_number VARCHAR(255),
    ADD COLUMN IF NOT EXISTS email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS primary_address VARCHAR(255),
    ADD COLUMN IF NOT EXISTS status VARCHAR(64),
    ADD COLUMN IF NOT EXISTS tier VARCHAR(64),
    ADD COLUMN IF NOT EXISTS tier_assigned_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS tier_assigned_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS tier_manual_override BOOLEAN,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS first_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS last_name VARCHAR(255);

ALTER TABLE IF EXISTS contact
    ADD COLUMN IF NOT EXISTS first_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS last_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS phone_number VARCHAR(255);

CREATE TABLE IF NOT EXISTS customer_vehicle (
    customer_id UUID NOT NULL,
    vin VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS party_external_identifier (
    party_id UUID NOT NULL,
    system_name VARCHAR(255) NOT NULL,
    identifier_value VARCHAR(255),
    PRIMARY KEY (party_id, system_name)
);

CREATE INDEX IF NOT EXISTS idx_customer_vehicle_customer
    ON customer_vehicle (customer_id);
