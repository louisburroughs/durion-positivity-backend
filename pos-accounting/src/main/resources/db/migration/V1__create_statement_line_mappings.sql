-- Create statement_line_mappings table for financial reporting
-- CAP-054: Period Close, Adjustments, and Reporting

CREATE TABLE statement_line_mappings (
    mapping_id UUID PRIMARY KEY,
    account_id VARCHAR(100) NOT NULL,
    account_name VARCHAR(255),
    statement_type VARCHAR(50) NOT NULL CHECK (statement_type IN ('INCOME_STATEMENT', 'BALANCE_SHEET')),
    statement_line_code VARCHAR(100) NOT NULL,
    line_description VARCHAR(255),
    display_order INTEGER,
    parent_line_code VARCHAR(100),
    operation VARCHAR(50) NOT NULL CHECK (operation IN ('SUM', 'SUBTRACT', 'NEGATE')),
    CONSTRAINT fk_statement_line_mapping_account FOREIGN KEY (account_id) 
        REFERENCES gl_accounts(account_id) ON DELETE CASCADE
);

-- Create indexes for efficient queries
CREATE INDEX idx_statement_line_mapping_type ON statement_line_mappings(statement_type);
CREATE INDEX idx_statement_line_mapping_account ON statement_line_mappings(account_id);
CREATE INDEX idx_statement_line_mapping_code ON statement_line_mappings(statement_line_code);

-- Insert default GAAP-aligned mappings for US standard chart of accounts
-- These can be customized per organization

-- Income Statement Mappings (Revenue)
INSERT INTO statement_line_mappings (mapping_id, account_id, account_name, statement_type, statement_line_code, line_description, display_order, operation) VALUES
(gen_random_uuid(), '4000', 'Sales Revenue', 'INCOME_STATEMENT', 'REVENUE_SALES', 'Revenue from product/service sales', 10, 'SUM'),
(gen_random_uuid(), '4100', 'Service Revenue', 'INCOME_STATEMENT', 'REVENUE_SERVICES', 'Revenue from services', 20, 'SUM'),
(gen_random_uuid(), '4900', 'Other Income', 'INCOME_STATEMENT', 'REVENUE_OTHER', 'Other operating income', 30, 'SUM');

-- Income Statement Mappings (Cost of Goods Sold)
INSERT INTO statement_line_mappings (mapping_id, account_id, account_name, statement_type, statement_line_code, line_description, display_order, operation) VALUES
(gen_random_uuid(), '5000', 'Cost of Goods Sold', 'INCOME_STATEMENT', 'EXPENSE_COGS', 'Direct costs of products sold', 100, 'SUM'),
(gen_random_uuid(), '5100', 'Labor Costs', 'INCOME_STATEMENT', 'EXPENSE_LABOR', 'Direct labor costs', 110, 'SUM');

-- Income Statement Mappings (Operating Expenses)
INSERT INTO statement_line_mappings (mapping_id, account_id, account_name, statement_type, statement_line_code, line_description, display_order, operation) VALUES
(gen_random_uuid(), '6000', 'Rent Expense', 'INCOME_STATEMENT', 'EXPENSE_RENT', 'Facility rent', 200, 'SUM'),
(gen_random_uuid(), '6100', 'Utilities Expense', 'INCOME_STATEMENT', 'EXPENSE_UTILITIES', 'Power, water, telecom', 210, 'SUM'),
(gen_random_uuid(), '6200', 'Marketing Expense', 'INCOME_STATEMENT', 'EXPENSE_MARKETING', 'Advertising and marketing', 220, 'SUM'),
(gen_random_uuid(), '6300', 'Payroll Expense', 'INCOME_STATEMENT', 'EXPENSE_PAYROLL', 'Employee salaries and wages', 230, 'SUM');

-- Balance Sheet Mappings (Assets - Current)
INSERT INTO statement_line_mappings (mapping_id, account_id, account_name, statement_type, statement_line_code, line_description, display_order, operation) VALUES
(gen_random_uuid(), '1000', 'Cash', 'BALANCE_SHEET', 'ASSET_CURRENT_CASH', 'Cash and cash equivalents', 10, 'SUM'),
(gen_random_uuid(), '1100', 'Accounts Receivable', 'BALANCE_SHEET', 'ASSET_CURRENT_AR', 'Customer receivables', 20, 'SUM'),
(gen_random_uuid(), '1200', 'Inventory', 'BALANCE_SHEET', 'ASSET_CURRENT_INVENTORY', 'Inventory on hand', 30, 'SUM');

-- Balance Sheet Mappings (Assets - Fixed)
INSERT INTO statement_line_mappings (mapping_id, account_id, account_name, statement_type, statement_line_code, line_description, display_order, operation) VALUES
(gen_random_uuid(), '1500', 'Equipment', 'BALANCE_SHEET', 'ASSET_FIXED_EQUIPMENT', 'Equipment and machinery', 100, 'SUM'),
(gen_random_uuid(), '1510', 'Accumulated Depreciation', 'BALANCE_SHEET', 'ASSET_FIXED_DEPRECIATION', 'Accumulated depreciation (contra)', 110, 'SUBTRACT');

-- Balance Sheet Mappings (Liabilities - Current)
INSERT INTO statement_line_mappings (mapping_id, account_id, account_name, statement_type, statement_line_code, line_description, display_order, operation) VALUES
(gen_random_uuid(), '2000', 'Accounts Payable', 'BALANCE_SHEET', 'LIABILITY_CURRENT_AP', 'Vendor payables', 200, 'SUM'),
(gen_random_uuid(), '2100', 'Accrued Expenses', 'BALANCE_SHEET', 'LIABILITY_CURRENT_ACCRUED', 'Accrued but unpaid expenses', 210, 'SUM');

-- Balance Sheet Mappings (Liabilities - Long-term)
INSERT INTO statement_line_mappings (mapping_id, account_id, account_name, statement_type, statement_line_code, line_description, display_order, operation) VALUES
(gen_random_uuid(), '2500', 'Long-term Debt', 'BALANCE_SHEET', 'LIABILITY_LONGTERM_DEBT', 'Long-term loans and notes', 300, 'SUM');

-- Balance Sheet Mappings (Equity)
INSERT INTO statement_line_mappings (mapping_id, account_id, account_name, statement_type, statement_line_code, line_description, display_order, operation) VALUES
(gen_random_uuid(), '3000', 'Owner''s Equity', 'BALANCE_SHEET', 'EQUITY_OWNERS', 'Owner''s capital', 400, 'SUM'),
(gen_random_uuid(), '3100', 'Retained Earnings', 'BALANCE_SHEET', 'EQUITY_RETAINED', 'Retained earnings', 410, 'SUM');

COMMENT ON TABLE statement_line_mappings IS 'Configurable mapping from GL accounts to financial statement lines (Income Statement, Balance Sheet). Supports GAAP, IFRS, or custom standards.';
COMMENT ON COLUMN statement_line_mappings.operation IS 'SUM: add account balance, SUBTRACT: subtract balance (contra accounts), NEGATE: negate before adding (reversal)';
