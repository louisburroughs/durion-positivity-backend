-- Create statement_line_mappings table for financial reporting
-- CAP-054: Period Close, Adjustments, and Reporting

CREATE TABLE statement_line_mappings (
    mapping_id UUID PRIMARY KEY,
    gl_account_id UUID NOT NULL,
    account_name VARCHAR(255),
    statement_type VARCHAR(50) NOT NULL CHECK (statement_type IN ('INCOME_STATEMENT', 'BALANCE_SHEET')),
    statement_line_code VARCHAR(100) NOT NULL,
    line_description VARCHAR(255),
    display_order INTEGER,
    parent_line_code VARCHAR(100),
    operation VARCHAR(50) NOT NULL CHECK (operation IN ('SUM', 'SUBTRACT', 'NEGATE')),
    CONSTRAINT fk_statement_line_mapping_account FOREIGN KEY (gl_account_id) 
        REFERENCES gl_account(gl_account_id) ON DELETE CASCADE
);

-- Create indexes for efficient queries
CREATE INDEX idx_statement_line_mapping_type ON statement_line_mappings(statement_type);
CREATE INDEX idx_statement_line_mapping_account ON statement_line_mappings(gl_account_id);
CREATE INDEX idx_statement_line_mapping_code ON statement_line_mappings(statement_line_code);

-- Note: Seed data removed - will be added via application bootstrap or separate data migration
-- Default GAAP-aligned mappings should reference actual GL account UUIDs from your system

COMMENT ON TABLE statement_line_mappings IS 'Configurable mapping from GL accounts to financial statement lines (Income Statement, Balance Sheet). Supports GAAP, IFRS, or custom standards.';
COMMENT ON COLUMN statement_line_mappings.operation IS 'SUM: add account balance, SUBTRACT: subtract balance (contra accounts), NEGATE: negate before adding (reversal)';
