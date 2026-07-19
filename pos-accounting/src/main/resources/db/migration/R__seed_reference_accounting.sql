-- Repeatable seed migration for accounting reference data.
-- Source: durion/scripts/seed-generator/generated-seed-sql/005_accounting.sql
SET TIME ZONE 'UTC';


-- GL accounts
-- Story H1 (Issue #934): working small-business COA with account_subtype /
-- reconcilable metadata. Pure upserts keyed on account_code so re-runs
-- backfill metadata on existing rows without changing their ids.
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-000000001000'::uuid, '1000', 'Cash', 'ASSET', 'BANK_CASH', FALSE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable,
    modified_at = NOW(),
    modified_by = 'seed-generator';
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-000000001090'::uuid, '1090', 'Undeposited Funds', 'ASSET', 'UNDEPOSITED_FUNDS', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable,
    modified_at = NOW(),
    modified_by = 'seed-generator';
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, created_at, created_by, modified_at, modified_by)
VALUES ('0f12890f-383d-b449-b555-bd4b37bf1f44'::uuid, '1200', 'Accounts Receivable', 'ASSET', 'RECEIVABLE', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable,
    modified_at = NOW(),
    modified_by = 'seed-generator';
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-000000002000'::uuid, '2000', 'Accounts Payable', 'LIABILITY', 'PAYABLE', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable,
    modified_at = NOW(),
    modified_by = 'seed-generator';
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-000000002200'::uuid, '2200', 'Sales Tax Payable', 'LIABILITY', 'TAX_PAYABLE', FALSE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable,
    modified_at = NOW(),
    modified_by = 'seed-generator';
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-000000002300'::uuid, '2300', 'Customer Credit Liability', 'LIABILITY', 'CURRENT_LIABILITY', FALSE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable,
    modified_at = NOW(),
    modified_by = 'seed-generator';
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, created_at, created_by, modified_at, modified_by)
VALUES ('b8798348-d3be-9582-7a6d-883ae3e64e66'::uuid, '4000', 'Service Revenue', 'REVENUE', 'SALES', FALSE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable,
    modified_at = NOW(),
    modified_by = 'seed-generator';
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-000000005000'::uuid, '5000', 'Cost of Goods Sold', 'EXPENSE', 'COST_OF_SALES', FALSE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable,
    modified_at = NOW(),
    modified_by = 'seed-generator';
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-000000006000'::uuid, '6000', 'Payment Processor Fees', 'EXPENSE', 'OPERATING_EXPENSE', FALSE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable,
    modified_at = NOW(),
    modified_by = 'seed-generator';

-- Posting categories
INSERT INTO posting_category (posting_category_id, category_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('70eb38c4-cf6a-992a-81c3-2a4c958a458a'::uuid, 'Order Revenue', 'ORDER_REVENUE', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (posting_category_id) DO UPDATE SET
    category_name = EXCLUDED.category_name,
    description = EXCLUDED.description,
    is_active = EXCLUDED.is_active,
    modified_at = NOW(),
    modified_by = 'seed-generator';

-- Story C1 (Issue #954, decision D-3): AR cash-receipt GL posting category.
-- Payment applications post Dr Undeposited Funds (1090) / Cr AR (1200);
-- accounts resolve through this category's mapping keys — never hardcoded.
INSERT INTO posting_category (posting_category_id, category_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000ca01'::uuid, 'PAYMENT_APPLICATION', 'AR cash receipt GL posting (Dr Undeposited Funds / Cr AR, decision D-3)', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (posting_category_id) DO UPDATE SET
    category_name = EXCLUDED.category_name,
    description = EXCLUDED.description,
    is_active = EXCLUDED.is_active,
    modified_at = NOW(),
    modified_by = 'seed-generator';

-- Mapping keys
INSERT INTO mapping_key (mapping_key_id, posting_category_id, key_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('05b9e38b-003d-5a02-ec1b-db48542ccc12'::uuid, '70eb38c4-cf6a-992a-81c3-2a4c958a458a'::uuid, 'DEFAULT', 'DEFAULT', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (mapping_key_id) DO UPDATE SET
    posting_category_id = EXCLUDED.posting_category_id,
    key_name = EXCLUDED.key_name,
    description = EXCLUDED.description,
    is_active = EXCLUDED.is_active,
    modified_at = NOW(),
    modified_by = 'seed-generator';

-- Story C1 (Issue #954): mapping keys for the PAYMENT_APPLICATION category.
INSERT INTO mapping_key (mapping_key_id, posting_category_id, key_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000ca02'::uuid, '5eed0acc-0000-4000-8000-00000000ca01'::uuid, 'UNDEPOSITED_FUNDS', 'Debit side of AR cash receipt (decision D-3)', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (mapping_key_id) DO UPDATE SET
    posting_category_id = EXCLUDED.posting_category_id,
    key_name = EXCLUDED.key_name,
    description = EXCLUDED.description,
    is_active = EXCLUDED.is_active,
    modified_at = NOW(),
    modified_by = 'seed-generator';
INSERT INTO mapping_key (mapping_key_id, posting_category_id, key_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000ca03'::uuid, '5eed0acc-0000-4000-8000-00000000ca01'::uuid, 'ACCOUNTS_RECEIVABLE', 'Credit side of AR cash receipt', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (mapping_key_id) DO UPDATE SET
    posting_category_id = EXCLUDED.posting_category_id,
    key_name = EXCLUDED.key_name,
    description = EXCLUDED.description,
    is_active = EXCLUDED.is_active,
    modified_at = NOW(),
    modified_by = 'seed-generator';

-- GL mappings
INSERT INTO gl_mapping (gl_mapping_id, source_system, external_code, posting_category_id, mapping_key_id, gl_account_id, effective_start_date, created_at, created_by)
VALUES ('c3d2b7cf-a895-077a-2a37-48115a2b7c22'::uuid, 'ORDER', 'ORDER_COMPLETED', '70eb38c4-cf6a-992a-81c3-2a4c958a458a'::uuid, '05b9e38b-003d-5a02-ec1b-db48542ccc12'::uuid, 'b8798348-d3be-9582-7a6d-883ae3e64e66'::uuid, NOW(), NOW(), 'seed-generator')
ON CONFLICT (gl_mapping_id) DO UPDATE SET
    source_system = EXCLUDED.source_system,
    external_code = EXCLUDED.external_code,
    posting_category_id = EXCLUDED.posting_category_id,
    mapping_key_id = EXCLUDED.mapping_key_id,
    gl_account_id = EXCLUDED.gl_account_id,
    effective_start_date = EXCLUDED.effective_start_date,
    effective_end_date = EXCLUDED.effective_end_date,
    dimensions = EXCLUDED.dimensions,
    created_by = 'seed-generator';

-- Story C1 (Issue #954): PAYMENT_APPLICATION account mappings. Fixed
-- effective_start_date (not NOW()) so re-runs stay idempotent and the
-- mapping always covers every posting date. 1090 = Undeposited Funds,
-- 1200 = Accounts Receivable (both seeded above by story H1).
INSERT INTO gl_mapping (gl_mapping_id, source_system, external_code, posting_category_id, mapping_key_id, gl_account_id, effective_start_date, created_at, created_by)
VALUES ('5eed0acc-0000-4000-8000-00000000ca04'::uuid, 'ACCOUNTING', 'PAYMENT_APPLICATION_UNDEPOSITED_FUNDS', '5eed0acc-0000-4000-8000-00000000ca01'::uuid, '5eed0acc-0000-4000-8000-00000000ca02'::uuid, '5eed0acc-0000-4000-8000-000000001090'::uuid, TIMESTAMP '2020-01-01 00:00:00', NOW(), 'seed-generator')
ON CONFLICT (gl_mapping_id) DO UPDATE SET
    source_system = EXCLUDED.source_system,
    external_code = EXCLUDED.external_code,
    posting_category_id = EXCLUDED.posting_category_id,
    mapping_key_id = EXCLUDED.mapping_key_id,
    gl_account_id = EXCLUDED.gl_account_id,
    effective_start_date = EXCLUDED.effective_start_date,
    effective_end_date = EXCLUDED.effective_end_date,
    dimensions = EXCLUDED.dimensions,
    created_by = 'seed-generator';
INSERT INTO gl_mapping (gl_mapping_id, source_system, external_code, posting_category_id, mapping_key_id, gl_account_id, effective_start_date, created_at, created_by)
VALUES ('5eed0acc-0000-4000-8000-00000000ca05'::uuid, 'ACCOUNTING', 'PAYMENT_APPLICATION_ACCOUNTS_RECEIVABLE', '5eed0acc-0000-4000-8000-00000000ca01'::uuid, '5eed0acc-0000-4000-8000-00000000ca03'::uuid, '0f12890f-383d-b449-b555-bd4b37bf1f44'::uuid, TIMESTAMP '2020-01-01 00:00:00', NOW(), 'seed-generator')
ON CONFLICT (gl_mapping_id) DO UPDATE SET
    source_system = EXCLUDED.source_system,
    external_code = EXCLUDED.external_code,
    posting_category_id = EXCLUDED.posting_category_id,
    mapping_key_id = EXCLUDED.mapping_key_id,
    gl_account_id = EXCLUDED.gl_account_id,
    effective_start_date = EXCLUDED.effective_start_date,
    effective_end_date = EXCLUDED.effective_end_date,
    dimensions = EXCLUDED.dimensions,
    created_by = 'seed-generator';

-- Default GL mappings
INSERT INTO default_gl_mapping (mapping_id, event_type, organization_id, debit_account_id, credit_account_id, description, active, created_at, created_by, modified_at, modified_by)
VALUES ('9a2f5863-9a58-c159-0a0f-85ff599dd791'::uuid, 'ORDER_CART_CREATE', NULL, '0f12890f-383d-b449-b555-bd4b37bf1f44'::uuid, 'b8798348-d3be-9582-7a6d-883ae3e64e66'::uuid, 'ORDER_CART_CREATE default mapping', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (mapping_id) DO UPDATE SET
    event_type = EXCLUDED.event_type,
    organization_id = EXCLUDED.organization_id,
    debit_account_id = EXCLUDED.debit_account_id,
    credit_account_id = EXCLUDED.credit_account_id,
    description = EXCLUDED.description,
    active = EXCLUDED.active,
    modified_at = NOW(),
    modified_by = 'seed-generator';

-- Statement line mappings
INSERT INTO statement_line_mappings (mapping_id, gl_account_id, account_name, statement_type, statement_line_code, line_description, display_order, operation)
VALUES ('3286527a-37f8-083e-c1d0-b8af8bab8afa'::uuid, 'b8798348-d3be-9582-7a6d-883ae3e64e66'::uuid, '4000', 'INCOME_STATEMENT', 'REVENUE', 'REVENUE', 1, 'SUM')
ON CONFLICT (mapping_id) DO UPDATE SET
    gl_account_id = EXCLUDED.gl_account_id,
    account_name = EXCLUDED.account_name,
    statement_type = EXCLUDED.statement_type,
    statement_line_code = EXCLUDED.statement_line_code,
    line_description = EXCLUDED.line_description,
    display_order = EXCLUDED.display_order,
    operation = EXCLUDED.operation;
