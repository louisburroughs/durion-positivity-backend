-- Adds missing customer-domain entity tables for fresh Flyway bootstrap parity.

CREATE TABLE IF NOT EXISTS person_party (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS commercial_party (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS contact (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS contact_point (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS contact_role_assignment (
    contact_id UUID NOT NULL,
    role_code VARCHAR(128) NOT NULL,
    PRIMARY KEY (contact_id, role_code)
);

CREATE TABLE IF NOT EXISTS communication_preference (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS party_alias (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS party_note (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS party_relationship (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS merge_audit (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS processing_log (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS promotion_counter (
    id UUID PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS promotion_redemption (
    id UUID PRIMARY KEY,
    promotion_code VARCHAR(128),
    workorder_id VARCHAR(36)
);

CREATE TABLE IF NOT EXISTS vehicle_projection (
    id UUID PRIMARY KEY
);
