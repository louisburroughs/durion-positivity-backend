-- Initial schema for Party and Contact entities
-- Issue #176: Party: Create Commercial Account
-- Issue #172: Contacts: Maintain Contact Roles and Primary Flags

-- Party table
CREATE TABLE party (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    party_number VARCHAR(255) NOT NULL UNIQUE,
    legal_name VARCHAR(500) NOT NULL,
    display_name VARCHAR(255),
    tax_id VARCHAR(100),
    billing_terms_id VARCHAR(100),
    party_type VARCHAR(50) NOT NULL DEFAULT 'ORGANIZATION',
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    parent_party_id BIGINT,
    primary_address VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_parent_party FOREIGN KEY (parent_party_id) REFERENCES party(id)
);

-- Contact table
CREATE TABLE contact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    party_id BIGINT NOT NULL,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    phone_number VARCHAR(50),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_contact_party FOREIGN KEY (party_id) REFERENCES party(id)
);

-- Party external identifier table
CREATE TABLE party_external_identifier (
    party_id BIGINT NOT NULL,
    system_name VARCHAR(255) NOT NULL,
    identifier_value VARCHAR(500),
    PRIMARY KEY (party_id, system_name),
    CONSTRAINT fk_ext_id_party FOREIGN KEY (party_id) REFERENCES party(id)
);

-- Party vehicle table
CREATE TABLE party_vehicle (
    party_id BIGINT NOT NULL,
    vin VARCHAR(17) NOT NULL,
    PRIMARY KEY (party_id, vin),
    CONSTRAINT fk_vehicle_party FOREIGN KEY (party_id) REFERENCES party(id)
);

-- Indexes
CREATE INDEX idx_party_legal_name ON party(legal_name);
CREATE INDEX idx_party_status ON party(status);
CREATE INDEX idx_party_type ON party(party_type);
CREATE INDEX idx_contact_party_id ON contact(party_id);
CREATE INDEX idx_contact_email ON contact(email);
CREATE INDEX idx_contact_active ON contact(active);
