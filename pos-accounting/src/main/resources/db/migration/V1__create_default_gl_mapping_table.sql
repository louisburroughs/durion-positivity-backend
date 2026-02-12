-- Default GL Account Mappings for event types without posting rules
-- Provides fallback GL accounts to prevent event processing failures

CREATE TABLE default_gl_mapping (
    mapping_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    organization_id UUID, -- NULL = applies to all organizations
    debit_account_id UUID NOT NULL,
    credit_account_id UUID NOT NULL,
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100) NOT NULL,
    modified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_by VARCHAR(100) NOT NULL,
    
    CONSTRAINT fk_default_gl_debit_account FOREIGN KEY (debit_account_id) 
        REFERENCES gl_account(gl_account_id),
    CONSTRAINT fk_default_gl_credit_account FOREIGN KEY (credit_account_id) 
        REFERENCES gl_account(gl_account_id)
);

-- Index for fast lookup by event type and organization
CREATE INDEX idx_default_gl_mapping_event_type ON default_gl_mapping(event_type, organization_id);
CREATE INDEX idx_default_gl_mapping_active ON default_gl_mapping(active);

-- Ensure only one active default per event type + organization combination
CREATE UNIQUE INDEX idx_default_gl_mapping_unique_active 
    ON default_gl_mapping(event_type, organization_id) 
    WHERE active = TRUE;

COMMENT ON TABLE default_gl_mapping IS 'Default GL account mappings for event types without explicit posting rules';
COMMENT ON COLUMN default_gl_mapping.organization_id IS 'NULL means applies to all organizations (global default)';
COMMENT ON COLUMN default_gl_mapping.active IS 'Only one active default per event_type + organization_id';
