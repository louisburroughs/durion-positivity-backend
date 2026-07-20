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

-- ============================================================================
-- Story F1c (Issue #963, decision D-13): processor settlement reconciliation.
-- The batched settlement JE posts Dr Cash (1000) / Dr Processor Fees (6000) /
-- Cr Undeposited Funds (1090, reused from C1) / Cr Settlement Suspense (2350).
-- Unmatched write-offs post Dr Settlement Suspense / Cr Settlement Adjustments
-- (4900). All accounts resolve through posting categories — never hardcoded.
-- ============================================================================

-- Settlement Suspense: clearing liability holding unattributed settlement gross
-- until a line is matched (reclassed to Undeposited) or written off.
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-000000002350'::uuid, '2350', 'Settlement Suspense', 'LIABILITY', 'CURRENT_LIABILITY', FALSE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable,
    modified_at = NOW(),
    modified_by = 'seed-generator';
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-000000004900'::uuid, '4900', 'Settlement Adjustments', 'REVENUE', 'OTHER', FALSE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable,
    modified_at = NOW(),
    modified_by = 'seed-generator';

-- Posting categories: SETTLEMENT (batched JE legs) + SETTLEMENT_ADJUSTMENT (write-offs).
INSERT INTO posting_category (posting_category_id, category_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000cf01'::uuid, 'SETTLEMENT', 'Batched processor settlement JE (decision D-13)', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (posting_category_id) DO UPDATE SET
    category_name = EXCLUDED.category_name, description = EXCLUDED.description, is_active = EXCLUDED.is_active,
    modified_at = NOW(), modified_by = 'seed-generator';
INSERT INTO posting_category (posting_category_id, category_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000cf10'::uuid, 'SETTLEMENT_ADJUSTMENT', 'Settlement line write-off adjustment (decision D-14)', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (posting_category_id) DO UPDATE SET
    category_name = EXCLUDED.category_name, description = EXCLUDED.description, is_active = EXCLUDED.is_active,
    modified_at = NOW(), modified_by = 'seed-generator';

-- Mapping keys for SETTLEMENT.
INSERT INTO mapping_key (mapping_key_id, posting_category_id, key_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000cf02'::uuid, '5eed0acc-0000-4000-8000-00000000cf01'::uuid, 'SETTLEMENT_CASH', 'Net bank payout (debit)', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (mapping_key_id) DO UPDATE SET posting_category_id = EXCLUDED.posting_category_id, key_name = EXCLUDED.key_name, description = EXCLUDED.description, is_active = EXCLUDED.is_active, modified_at = NOW(), modified_by = 'seed-generator';
INSERT INTO mapping_key (mapping_key_id, posting_category_id, key_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000cf03'::uuid, '5eed0acc-0000-4000-8000-00000000cf01'::uuid, 'PROCESSOR_FEES', 'Processor fees (debit)', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (mapping_key_id) DO UPDATE SET posting_category_id = EXCLUDED.posting_category_id, key_name = EXCLUDED.key_name, description = EXCLUDED.description, is_active = EXCLUDED.is_active, modified_at = NOW(), modified_by = 'seed-generator';
INSERT INTO mapping_key (mapping_key_id, posting_category_id, key_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000cf04'::uuid, '5eed0acc-0000-4000-8000-00000000cf01'::uuid, 'UNDEPOSITED_FUNDS', 'Matched receipts cleared (credit)', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (mapping_key_id) DO UPDATE SET posting_category_id = EXCLUDED.posting_category_id, key_name = EXCLUDED.key_name, description = EXCLUDED.description, is_active = EXCLUDED.is_active, modified_at = NOW(), modified_by = 'seed-generator';
INSERT INTO mapping_key (mapping_key_id, posting_category_id, key_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000cf05'::uuid, '5eed0acc-0000-4000-8000-00000000cf01'::uuid, 'SETTLEMENT_SUSPENSE', 'Unmatched gross parked (credit)', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (mapping_key_id) DO UPDATE SET posting_category_id = EXCLUDED.posting_category_id, key_name = EXCLUDED.key_name, description = EXCLUDED.description, is_active = EXCLUDED.is_active, modified_at = NOW(), modified_by = 'seed-generator';
INSERT INTO mapping_key (mapping_key_id, posting_category_id, key_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000cf11'::uuid, '5eed0acc-0000-4000-8000-00000000cf10'::uuid, 'SETTLEMENT_ADJUSTMENT', 'Write-off adjustment account', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (mapping_key_id) DO UPDATE SET posting_category_id = EXCLUDED.posting_category_id, key_name = EXCLUDED.key_name, description = EXCLUDED.description, is_active = EXCLUDED.is_active, modified_at = NOW(), modified_by = 'seed-generator';

-- GL mappings (fixed effective_start_date for idempotent re-runs).
INSERT INTO gl_mapping (gl_mapping_id, source_system, external_code, posting_category_id, mapping_key_id, gl_account_id, effective_start_date, created_at, created_by)
VALUES ('5eed0acc-0000-4000-8000-00000000cf06'::uuid, 'ACCOUNTING', 'SETTLEMENT_CASH', '5eed0acc-0000-4000-8000-00000000cf01'::uuid, '5eed0acc-0000-4000-8000-00000000cf02'::uuid, '5eed0acc-0000-4000-8000-000000001000'::uuid, TIMESTAMP '2020-01-01 00:00:00', NOW(), 'seed-generator')
ON CONFLICT (gl_mapping_id) DO UPDATE SET source_system = EXCLUDED.source_system, external_code = EXCLUDED.external_code, posting_category_id = EXCLUDED.posting_category_id, mapping_key_id = EXCLUDED.mapping_key_id, gl_account_id = EXCLUDED.gl_account_id, effective_start_date = EXCLUDED.effective_start_date, created_by = 'seed-generator';
INSERT INTO gl_mapping (gl_mapping_id, source_system, external_code, posting_category_id, mapping_key_id, gl_account_id, effective_start_date, created_at, created_by)
VALUES ('5eed0acc-0000-4000-8000-00000000cf07'::uuid, 'ACCOUNTING', 'SETTLEMENT_PROCESSOR_FEES', '5eed0acc-0000-4000-8000-00000000cf01'::uuid, '5eed0acc-0000-4000-8000-00000000cf03'::uuid, '5eed0acc-0000-4000-8000-000000006000'::uuid, TIMESTAMP '2020-01-01 00:00:00', NOW(), 'seed-generator')
ON CONFLICT (gl_mapping_id) DO UPDATE SET source_system = EXCLUDED.source_system, external_code = EXCLUDED.external_code, posting_category_id = EXCLUDED.posting_category_id, mapping_key_id = EXCLUDED.mapping_key_id, gl_account_id = EXCLUDED.gl_account_id, effective_start_date = EXCLUDED.effective_start_date, created_by = 'seed-generator';
INSERT INTO gl_mapping (gl_mapping_id, source_system, external_code, posting_category_id, mapping_key_id, gl_account_id, effective_start_date, created_at, created_by)
VALUES ('5eed0acc-0000-4000-8000-00000000cf08'::uuid, 'ACCOUNTING', 'SETTLEMENT_UNDEPOSITED_FUNDS', '5eed0acc-0000-4000-8000-00000000cf01'::uuid, '5eed0acc-0000-4000-8000-00000000cf04'::uuid, '5eed0acc-0000-4000-8000-000000001090'::uuid, TIMESTAMP '2020-01-01 00:00:00', NOW(), 'seed-generator')
ON CONFLICT (gl_mapping_id) DO UPDATE SET source_system = EXCLUDED.source_system, external_code = EXCLUDED.external_code, posting_category_id = EXCLUDED.posting_category_id, mapping_key_id = EXCLUDED.mapping_key_id, gl_account_id = EXCLUDED.gl_account_id, effective_start_date = EXCLUDED.effective_start_date, created_by = 'seed-generator';
INSERT INTO gl_mapping (gl_mapping_id, source_system, external_code, posting_category_id, mapping_key_id, gl_account_id, effective_start_date, created_at, created_by)
VALUES ('5eed0acc-0000-4000-8000-00000000cf09'::uuid, 'ACCOUNTING', 'SETTLEMENT_SUSPENSE', '5eed0acc-0000-4000-8000-00000000cf01'::uuid, '5eed0acc-0000-4000-8000-00000000cf05'::uuid, '5eed0acc-0000-4000-8000-000000002350'::uuid, TIMESTAMP '2020-01-01 00:00:00', NOW(), 'seed-generator')
ON CONFLICT (gl_mapping_id) DO UPDATE SET source_system = EXCLUDED.source_system, external_code = EXCLUDED.external_code, posting_category_id = EXCLUDED.posting_category_id, mapping_key_id = EXCLUDED.mapping_key_id, gl_account_id = EXCLUDED.gl_account_id, effective_start_date = EXCLUDED.effective_start_date, created_by = 'seed-generator';
INSERT INTO gl_mapping (gl_mapping_id, source_system, external_code, posting_category_id, mapping_key_id, gl_account_id, effective_start_date, created_at, created_by)
VALUES ('5eed0acc-0000-4000-8000-00000000cf12'::uuid, 'ACCOUNTING', 'SETTLEMENT_ADJUSTMENT', '5eed0acc-0000-4000-8000-00000000cf10'::uuid, '5eed0acc-0000-4000-8000-00000000cf11'::uuid, '5eed0acc-0000-4000-8000-000000004900'::uuid, TIMESTAMP '2020-01-01 00:00:00', NOW(), 'seed-generator')
ON CONFLICT (gl_mapping_id) DO UPDATE SET source_system = EXCLUDED.source_system, external_code = EXCLUDED.external_code, posting_category_id = EXCLUDED.posting_category_id, mapping_key_id = EXCLUDED.mapping_key_id, gl_account_id = EXCLUDED.gl_account_id, effective_start_date = EXCLUDED.effective_start_date, created_by = 'seed-generator';

-- ============================================================================
-- Story F2 (Issue #965, decision D-6): manual CSV bank reconciliation.
-- Adjustments post a real balanced JE: Dr/Cr the reconciled cash account against
-- the adjustment type's mapped counter account, resolved through the
-- BANK_RECONCILIATION posting category (one mapping key per adjustment type).
--   BANK_FEE         -> 6010 Bank Service Charges (expense)
--   NSF_FEE          -> 6020 NSF Fees (expense)
--   INTEREST_EARNED  -> 4920 Interest Income (revenue)
--   FLOAT_ADJUSTMENT -> 2360 Bank Reconciliation Adjustments (liability clearing)
--   OTHER            -> 2360 Bank Reconciliation Adjustments (liability clearing)
-- 2360 is a non-P&L clearing account (LIABILITY) so reconciliation noise never
-- lands in revenue/expense reporting.
-- ============================================================================

INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-000000006010'::uuid, '6010', 'Bank Service Charges', 'EXPENSE', 'OPERATING_EXPENSE', FALSE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name, account_type = EXCLUDED.account_type, account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable, modified_at = NOW(), modified_by = 'seed-generator';
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-000000006020'::uuid, '6020', 'NSF Fees', 'EXPENSE', 'OPERATING_EXPENSE', FALSE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name, account_type = EXCLUDED.account_type, account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable, modified_at = NOW(), modified_by = 'seed-generator';
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-000000004920'::uuid, '4920', 'Interest Income', 'REVENUE', 'REVENUE', FALSE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name, account_type = EXCLUDED.account_type, account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable, modified_at = NOW(), modified_by = 'seed-generator';
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-000000002360'::uuid, '2360', 'Bank Reconciliation Adjustments', 'LIABILITY', 'CURRENT_LIABILITY', FALSE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name, account_type = EXCLUDED.account_type, account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable, modified_at = NOW(), modified_by = 'seed-generator';

-- Posting category: BANK_RECONCILIATION (one mapping key per adjustment type).
INSERT INTO posting_category (posting_category_id, category_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000d001'::uuid, 'BANK_RECONCILIATION', 'Bank reconciliation adjustment counter accounts (decision D-6)', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (posting_category_id) DO UPDATE SET
    category_name = EXCLUDED.category_name, description = EXCLUDED.description, is_active = EXCLUDED.is_active,
    modified_at = NOW(), modified_by = 'seed-generator';

-- Mapping keys (key_name == adjustment type name; resolved by name at posting time).
INSERT INTO mapping_key (mapping_key_id, posting_category_id, key_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000d002'::uuid, '5eed0acc-0000-4000-8000-00000000d001'::uuid, 'BANK_FEE', 'Bank service charge counter (expense)', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (mapping_key_id) DO UPDATE SET posting_category_id = EXCLUDED.posting_category_id, key_name = EXCLUDED.key_name, description = EXCLUDED.description, is_active = EXCLUDED.is_active, modified_at = NOW(), modified_by = 'seed-generator';
INSERT INTO mapping_key (mapping_key_id, posting_category_id, key_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000d003'::uuid, '5eed0acc-0000-4000-8000-00000000d001'::uuid, 'NSF_FEE', 'Returned-item fee counter (expense)', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (mapping_key_id) DO UPDATE SET posting_category_id = EXCLUDED.posting_category_id, key_name = EXCLUDED.key_name, description = EXCLUDED.description, is_active = EXCLUDED.is_active, modified_at = NOW(), modified_by = 'seed-generator';
INSERT INTO mapping_key (mapping_key_id, posting_category_id, key_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000d004'::uuid, '5eed0acc-0000-4000-8000-00000000d001'::uuid, 'INTEREST_EARNED', 'Interest income counter (revenue)', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (mapping_key_id) DO UPDATE SET posting_category_id = EXCLUDED.posting_category_id, key_name = EXCLUDED.key_name, description = EXCLUDED.description, is_active = EXCLUDED.is_active, modified_at = NOW(), modified_by = 'seed-generator';
INSERT INTO mapping_key (mapping_key_id, posting_category_id, key_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000d005'::uuid, '5eed0acc-0000-4000-8000-00000000d001'::uuid, 'FLOAT_ADJUSTMENT', 'Float/timing correction counter (clearing)', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (mapping_key_id) DO UPDATE SET posting_category_id = EXCLUDED.posting_category_id, key_name = EXCLUDED.key_name, description = EXCLUDED.description, is_active = EXCLUDED.is_active, modified_at = NOW(), modified_by = 'seed-generator';
INSERT INTO mapping_key (mapping_key_id, posting_category_id, key_name, description, is_active, created_at, created_by, modified_at, modified_by)
VALUES ('5eed0acc-0000-4000-8000-00000000d006'::uuid, '5eed0acc-0000-4000-8000-00000000d001'::uuid, 'OTHER', 'Other reconciling adjustment counter (clearing)', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (mapping_key_id) DO UPDATE SET posting_category_id = EXCLUDED.posting_category_id, key_name = EXCLUDED.key_name, description = EXCLUDED.description, is_active = EXCLUDED.is_active, modified_at = NOW(), modified_by = 'seed-generator';

-- GL mappings (fixed effective_start_date for idempotent re-runs).
INSERT INTO gl_mapping (gl_mapping_id, source_system, external_code, posting_category_id, mapping_key_id, gl_account_id, effective_start_date, created_at, created_by)
VALUES ('5eed0acc-0000-4000-8000-00000000d011'::uuid, 'ACCOUNTING', 'BANK_RECON_BANK_FEE', '5eed0acc-0000-4000-8000-00000000d001'::uuid, '5eed0acc-0000-4000-8000-00000000d002'::uuid, '5eed0acc-0000-4000-8000-000000006010'::uuid, TIMESTAMP '2020-01-01 00:00:00', NOW(), 'seed-generator')
ON CONFLICT (gl_mapping_id) DO UPDATE SET source_system = EXCLUDED.source_system, external_code = EXCLUDED.external_code, posting_category_id = EXCLUDED.posting_category_id, mapping_key_id = EXCLUDED.mapping_key_id, gl_account_id = EXCLUDED.gl_account_id, effective_start_date = EXCLUDED.effective_start_date, created_by = 'seed-generator';
INSERT INTO gl_mapping (gl_mapping_id, source_system, external_code, posting_category_id, mapping_key_id, gl_account_id, effective_start_date, created_at, created_by)
VALUES ('5eed0acc-0000-4000-8000-00000000d012'::uuid, 'ACCOUNTING', 'BANK_RECON_NSF_FEE', '5eed0acc-0000-4000-8000-00000000d001'::uuid, '5eed0acc-0000-4000-8000-00000000d003'::uuid, '5eed0acc-0000-4000-8000-000000006020'::uuid, TIMESTAMP '2020-01-01 00:00:00', NOW(), 'seed-generator')
ON CONFLICT (gl_mapping_id) DO UPDATE SET source_system = EXCLUDED.source_system, external_code = EXCLUDED.external_code, posting_category_id = EXCLUDED.posting_category_id, mapping_key_id = EXCLUDED.mapping_key_id, gl_account_id = EXCLUDED.gl_account_id, effective_start_date = EXCLUDED.effective_start_date, created_by = 'seed-generator';
INSERT INTO gl_mapping (gl_mapping_id, source_system, external_code, posting_category_id, mapping_key_id, gl_account_id, effective_start_date, created_at, created_by)
VALUES ('5eed0acc-0000-4000-8000-00000000d013'::uuid, 'ACCOUNTING', 'BANK_RECON_INTEREST_EARNED', '5eed0acc-0000-4000-8000-00000000d001'::uuid, '5eed0acc-0000-4000-8000-00000000d004'::uuid, '5eed0acc-0000-4000-8000-000000004920'::uuid, TIMESTAMP '2020-01-01 00:00:00', NOW(), 'seed-generator')
ON CONFLICT (gl_mapping_id) DO UPDATE SET source_system = EXCLUDED.source_system, external_code = EXCLUDED.external_code, posting_category_id = EXCLUDED.posting_category_id, mapping_key_id = EXCLUDED.mapping_key_id, gl_account_id = EXCLUDED.gl_account_id, effective_start_date = EXCLUDED.effective_start_date, created_by = 'seed-generator';
INSERT INTO gl_mapping (gl_mapping_id, source_system, external_code, posting_category_id, mapping_key_id, gl_account_id, effective_start_date, created_at, created_by)
VALUES ('5eed0acc-0000-4000-8000-00000000d014'::uuid, 'ACCOUNTING', 'BANK_RECON_FLOAT_ADJUSTMENT', '5eed0acc-0000-4000-8000-00000000d001'::uuid, '5eed0acc-0000-4000-8000-00000000d005'::uuid, '5eed0acc-0000-4000-8000-000000002360'::uuid, TIMESTAMP '2020-01-01 00:00:00', NOW(), 'seed-generator')
ON CONFLICT (gl_mapping_id) DO UPDATE SET source_system = EXCLUDED.source_system, external_code = EXCLUDED.external_code, posting_category_id = EXCLUDED.posting_category_id, mapping_key_id = EXCLUDED.mapping_key_id, gl_account_id = EXCLUDED.gl_account_id, effective_start_date = EXCLUDED.effective_start_date, created_by = 'seed-generator';
INSERT INTO gl_mapping (gl_mapping_id, source_system, external_code, posting_category_id, mapping_key_id, gl_account_id, effective_start_date, created_at, created_by)
VALUES ('5eed0acc-0000-4000-8000-00000000d015'::uuid, 'ACCOUNTING', 'BANK_RECON_OTHER', '5eed0acc-0000-4000-8000-00000000d001'::uuid, '5eed0acc-0000-4000-8000-00000000d006'::uuid, '5eed0acc-0000-4000-8000-000000002360'::uuid, TIMESTAMP '2020-01-01 00:00:00', NOW(), 'seed-generator')
ON CONFLICT (gl_mapping_id) DO UPDATE SET source_system = EXCLUDED.source_system, external_code = EXCLUDED.external_code, posting_category_id = EXCLUDED.posting_category_id, mapping_key_id = EXCLUDED.mapping_key_id, gl_account_id = EXCLUDED.gl_account_id, effective_start_date = EXCLUDED.effective_start_date, created_by = 'seed-generator';
