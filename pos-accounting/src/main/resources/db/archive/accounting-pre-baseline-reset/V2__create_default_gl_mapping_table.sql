-- Default GL Account Mappings for event types without posting rules
-- Provides fallback GL accounts to prevent event processing failures

CREATE TABLE IF NOT EXISTS gl_account (
    gl_account_id UUID PRIMARY KEY,
    account_code VARCHAR(20) NOT NULL UNIQUE,
    account_name VARCHAR(100) NOT NULL,
    account_type VARCHAR(20) NOT NULL,
    description VARCHAR(500),
    parent_account_id UUID,
    activation_date TIMESTAMP,
    deactivation_date TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NOT NULL DEFAULT 'system',
    modified_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_by VARCHAR(50) NOT NULL DEFAULT 'system',
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_gl_account_parent FOREIGN KEY (parent_account_id)
        REFERENCES gl_account(gl_account_id)
);

CREATE INDEX IF NOT EXISTS idx_account_code ON gl_account(account_code);
CREATE INDEX IF NOT EXISTS idx_account_type ON gl_account(account_type);
CREATE INDEX IF NOT EXISTS idx_activation_date ON gl_account(activation_date);
CREATE INDEX IF NOT EXISTS idx_deactivation_date ON gl_account(deactivation_date);

CREATE TABLE IF NOT EXISTS posting_category (
    posting_category_id UUID PRIMARY KEY,
    category_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NOT NULL DEFAULT 'system',
    modified_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_by VARCHAR(50) NOT NULL DEFAULT 'system'
);

CREATE INDEX IF NOT EXISTS idx_category_name ON posting_category(category_name);

CREATE TABLE IF NOT EXISTS mapping_key (
    mapping_key_id UUID PRIMARY KEY,
    posting_category_id UUID NOT NULL,
    key_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NOT NULL DEFAULT 'system',
    modified_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_by VARCHAR(50) NOT NULL DEFAULT 'system',
    CONSTRAINT fk_mapping_key_category FOREIGN KEY (posting_category_id)
        REFERENCES posting_category(posting_category_id)
);

CREATE INDEX IF NOT EXISTS idx_mapping_key_category ON mapping_key(posting_category_id);
CREATE INDEX IF NOT EXISTS idx_mapping_key_name ON mapping_key(key_name);

CREATE TABLE IF NOT EXISTS gl_mapping (
    gl_mapping_id UUID PRIMARY KEY,
    source_system VARCHAR(50) NOT NULL,
    external_code VARCHAR(100) NOT NULL,
    posting_category_id UUID,
    mapping_key_id UUID,
    gl_account_id UUID NOT NULL,
    effective_start_date TIMESTAMP NOT NULL,
    effective_end_date TIMESTAMP,
    dimensions TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NOT NULL DEFAULT 'system',
    CONSTRAINT fk_gl_mapping_posting_category FOREIGN KEY (posting_category_id)
        REFERENCES posting_category(posting_category_id),
    CONSTRAINT fk_gl_mapping_mapping_key FOREIGN KEY (mapping_key_id)
        REFERENCES mapping_key(mapping_key_id),
    CONSTRAINT fk_gl_mapping_gl_account FOREIGN KEY (gl_account_id)
        REFERENCES gl_account(gl_account_id)
);

CREATE INDEX IF NOT EXISTS idx_gl_mapping_category_key ON gl_mapping(posting_category_id, mapping_key_id);
CREATE INDEX IF NOT EXISTS idx_gl_mapping_effective_from ON gl_mapping(effective_start_date);
CREATE INDEX IF NOT EXISTS idx_gl_mapping_effective_to ON gl_mapping(effective_end_date);
CREATE INDEX IF NOT EXISTS idx_gl_mapping_gl_account ON gl_mapping(gl_account_id);
CREATE INDEX IF NOT EXISTS idx_gl_mapping_source_code ON gl_mapping(source_system, external_code);

CREATE TABLE IF NOT EXISTS default_gl_mapping (
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
CREATE INDEX IF NOT EXISTS idx_default_gl_mapping_event_type ON default_gl_mapping(event_type, organization_id);
CREATE INDEX IF NOT EXISTS idx_default_gl_mapping_active ON default_gl_mapping(active);

-- Ensure only one active default per event type + organization combination
CREATE UNIQUE INDEX IF NOT EXISTS idx_default_gl_mapping_unique_active
    ON default_gl_mapping(event_type, organization_id) 
    WHERE active = TRUE;

COMMENT ON TABLE default_gl_mapping IS 'Default GL account mappings for event types without explicit posting rules';
COMMENT ON COLUMN default_gl_mapping.organization_id IS 'NULL means applies to all organizations (global default)';
COMMENT ON COLUMN default_gl_mapping.active IS 'Only one active default per event_type + organization_id';
