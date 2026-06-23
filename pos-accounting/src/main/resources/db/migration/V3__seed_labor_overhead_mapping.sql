-- CAP-316: representative Labor & Overhead report-line -> GL-account mapping.
--
-- The full line->account set is authored by the Accounting domain (per the CAP-316 spec); this seeds a
-- representative subset so the report endpoint is demonstrably wired and testable. Unmapped lines return
-- 0 (acceptance criteria allow this). The mapping reuses the persisted statement_line_mappings table
-- under a new statement_type, LABOR_OVERHEAD.

-- Allow the new statement type on the existing check constraint.
ALTER TABLE statement_line_mappings
    DROP CONSTRAINT IF EXISTS statement_line_mappings_statement_type_check;
ALTER TABLE statement_line_mappings
    ADD CONSTRAINT statement_line_mappings_statement_type_check
        CHECK (statement_type IN ('INCOME_STATEMENT', 'BALANCE_SHEET', 'LABOR_OVERHEAD'));

-- Representative GL accounts for retread-plant labor & overhead lines.
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, version, created_at, created_by, modified_at, modified_by)
VALUES
    ('a1000000-0000-7000-8000-000000000001'::uuid, '6010', 'Retread Plant Hourly Wages', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000002'::uuid, '6110', 'Retread Plant FICA Expense', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000003'::uuid, '6400', 'Retread Plant Utilities', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000004'::uuid, '6450', 'Retread MRT Equipment Depreciation (USD)', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000005'::uuid, '6900', 'Rubber Dust Sales Income', 'REVENUE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    modified_at = NOW(),
    modified_by = 'seed-generator';

-- Map representative leaf line codes to those accounts (operation SUM; sign comes from debit-credit net).
INSERT INTO statement_line_mappings (mapping_id, gl_account_id, account_name, statement_type, statement_line_code, line_description, display_order, operation)
VALUES
    ('b1000000-0000-7000-8000-000000000001'::uuid, 'a1000000-0000-7000-8000-000000000001'::uuid, '6010', 'LABOR_OVERHEAD', '1.1.1', 'Hourly wages and bonuses', 1, 'SUM'),
    ('b1000000-0000-7000-8000-000000000002'::uuid, 'a1000000-0000-7000-8000-000000000002'::uuid, '6110', 'LABOR_OVERHEAD', '1.3.1', 'FICA', 2, 'SUM'),
    ('b1000000-0000-7000-8000-000000000003'::uuid, 'a1000000-0000-7000-8000-000000000003'::uuid, '6400', 'LABOR_OVERHEAD', '2.10', 'Utilities - gas, electric, water', 3, 'SUM'),
    ('b1000000-0000-7000-8000-000000000004'::uuid, 'a1000000-0000-7000-8000-000000000004'::uuid, '6450', 'LABOR_OVERHEAD', '2.11.4', 'Depreciation - MRT process equipment ($US only)', 4, 'SUM'),
    ('b1000000-0000-7000-8000-000000000005'::uuid, 'a1000000-0000-7000-8000-000000000005'::uuid, '6900', 'LABOR_OVERHEAD', '2.14', 'Income from rubber dust sales', 5, 'SUM')
ON CONFLICT (mapping_id) DO UPDATE SET
    gl_account_id = EXCLUDED.gl_account_id,
    account_name = EXCLUDED.account_name,
    statement_type = EXCLUDED.statement_type,
    statement_line_code = EXCLUDED.statement_line_code,
    line_description = EXCLUDED.line_description,
    display_order = EXCLUDED.display_order,
    operation = EXCLUDED.operation;
