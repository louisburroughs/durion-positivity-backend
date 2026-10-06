-- The accounting tenant template (ADR-0062 §6; CAP:550 S37, #2526).
--
-- These rows are DATA IN THE PLATFORM TENANT, not any shop's books. Every tenant receives a copy of
-- them from AccountingTemplateApplier: when its tenant.created fact arrives, and at each start
-- through AccountingTemplateStartupSweep. The copy is add-only: a row a tenant already holds, or
-- has changed, is never overwritten, so this file may keep ON CONFLICT ... DO UPDATE: the template
-- follows the file, and only the template.
--
-- Rules for whoever adds to this file:
--   * Bind the platform tenant only. Never insert a row for another tenant from here.
--   * Ids are md5('accounting-template:<platform tenant>:<KIND>:<natural key>')::uuid, so no
--     template id can equal a tenant row's id (primary keys are the id alone) and a rerun finds
--     the same row. KIND and natural key are the applier's entry key:
--       ACCOUNT:<code>                      CATEGORY:<name>
--       MAPPING_KEY:<category>/<key>        GL_MAPPING:<category>/<key>
--       DEFAULT_GL_MAPPING:<event type>     STATEMENT_LINE:<statement type>:<account code>
--   * References are by natural key through the same expression, never a literal id and never a
--     sub-select: Flyway runs as the owner, which row-level security does not restrict, so
--     looking an account up by its code would find every tenant's account with that code.
--   * Effective date: accounts are active, and GL mappings effective, from 2020-01-01, so a
--     tenant's first posting is covered whatever its date.
--   * The retread-plant add-on at the end is in the template but is not part of the generic
--     chart: RetreadPlantAddOnSource applies it only to a tenant that has chosen it.
SELECT set_config('app.current_tenant', '01900000-0000-7000-8000-000000000000', true);
SET TIME ZONE 'UTC';

-- ============================================================================
-- Generic chart: every tenant receives everything from here to the add-on section.
-- ============================================================================

-- GL accounts: the working small-business chart (stories H1 #934, C1 #954, F1c #963, F2 #965,
-- parity-C1 #975, #1043, G3 #1083, #1843). Posting never names an account: it resolves one through a
-- posting category and mapping key below.
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, activation_date, version, created_at, created_by, modified_at, modified_by)
SELECT md5('accounting-template:01900000-0000-7000-8000-000000000000:ACCOUNT:' || t.code)::uuid, t.code, t.name, t.type, t.subtype, t.reconcilable, TIMESTAMP '2020-01-01 00:00:00', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'
FROM (VALUES
    ('1000', 'Cash', 'ASSET', 'BANK_CASH', TRUE),
    ('1090', 'Undeposited Funds', 'ASSET', 'UNDEPOSITED_FUNDS', TRUE),
    ('1095', 'Register Cash Clearing', 'ASSET', 'CURRENT_ASSET', FALSE),
    ('1200', 'Accounts Receivable', 'ASSET', 'RECEIVABLE', TRUE),
    ('1300', 'Inventory', 'ASSET', 'CURRENT_ASSET', FALSE),
    ('2000', 'Accounts Payable', 'LIABILITY', 'PAYABLE', TRUE),
    ('2200', 'Sales Tax Payable', 'LIABILITY', 'TAX_PAYABLE', FALSE),
    ('2300', 'Customer Credit Liability', 'LIABILITY', 'CURRENT_LIABILITY', FALSE),
    ('2350', 'Settlement Suspense', 'LIABILITY', 'CURRENT_LIABILITY', FALSE),
    ('2360', 'Bank Reconciliation Adjustments', 'LIABILITY', 'CURRENT_LIABILITY', FALSE),
    ('4000', 'Service Revenue', 'REVENUE', 'SALES', FALSE),
    ('4900', 'Settlement Adjustments', 'REVENUE', 'OTHER', FALSE),
    ('4920', 'Interest Income', 'REVENUE', 'OTHER', FALSE),
    ('4930', 'Cash Over', 'REVENUE', 'OTHER', FALSE),
    ('5000', 'Cost of Goods Sold', 'EXPENSE', 'COST_OF_SALES', FALSE),
    ('5100', 'Inventory Shrinkage', 'EXPENSE', 'COST_OF_SALES', FALSE),
    ('6000', 'Payment Processor Fees', 'EXPENSE', 'OPERATING_EXPENSE', FALSE),
    ('6020', 'NSF Fees', 'EXPENSE', 'OPERATING_EXPENSE', FALSE),
    ('6030', 'Bank Service Charges', 'EXPENSE', 'OPERATING_EXPENSE', FALSE),
    ('6115', 'Cash Short', 'EXPENSE', 'OPERATING_EXPENSE', FALSE)
) AS t(code, name, type, subtype, reconcilable)
ON CONFLICT (tenant_id, account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable,
    activation_date = EXCLUDED.activation_date,
    modified_at = NOW(),
    modified_by = 'seed-generator';

-- GL accounts: the CAP-316 labour and overhead chart every tenant receives (AW30). Numbers and names
-- are the ones V2__seed_accounting.sql gave the alpha default tenant, so that tenant adopts its rows
-- instead of clashing with them; S15's AW30 renumbering changes them here and there together.
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, activation_date, version, created_at, created_by, modified_at, modified_by)
SELECT md5('accounting-template:01900000-0000-7000-8000-000000000000:ACCOUNT:' || t.code)::uuid, t.code, t.name, t.type, t.subtype, t.reconcilable, TIMESTAMP '2020-01-01 00:00:00', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'
FROM (VALUES
    ('6010', 'Retread Plant Hourly Wages', 'EXPENSE', NULL::text, FALSE),
    ('6015', 'Retread Plant Management Salaries', 'EXPENSE', NULL::text, FALSE),
    ('6025', 'Retread Plant Contract & Temp Labor', 'EXPENSE', NULL::text, FALSE),
    ('6110', 'Retread Plant FICA Expense', 'EXPENSE', NULL::text, FALSE),
    ('6120', 'Retread Plant Federal Unemployment Tax', 'EXPENSE', NULL::text, FALSE),
    ('6130', 'Retread Plant State Unemployment Tax', 'EXPENSE', NULL::text, FALSE),
    ('6140', 'Retread Plant Medical & Life Insurance', 'EXPENSE', NULL::text, FALSE),
    ('6150', 'Retread Plant Retirement Contributions', 'EXPENSE', NULL::text, FALSE),
    ('6160', 'Retread Plant Workers Comp Insurance', 'EXPENSE', NULL::text, FALSE),
    ('6170', 'Retread Plant Uniforms & Laundry', 'EXPENSE', NULL::text, FALSE),
    ('6200', 'Retread Building Depreciation', 'EXPENSE', NULL::text, FALSE),
    ('6210', 'Retread Building Maintenance', 'EXPENSE', NULL::text, FALSE),
    ('6220', 'Retread Building Rent', 'EXPENSE', NULL::text, FALSE),
    ('6230', 'Retread Plant Training Costs', 'EXPENSE', NULL::text, FALSE),
    ('6240', 'Retread Plant Recruiting & Employment Advertising', 'EXPENSE', NULL::text, FALSE),
    ('6250', 'Retread Plant Vehicle Gas & Oil', 'EXPENSE', NULL::text, FALSE),
    ('6255', 'Retread Plant Vehicle Maintenance', 'EXPENSE', NULL::text, FALSE),
    ('6260', 'Retread Plant Vehicle Taxes', 'EXPENSE', NULL::text, FALSE),
    ('6265', 'Retread Plant Vehicle Depreciation', 'EXPENSE', NULL::text, FALSE),
    ('6270', 'Retread Plant Vehicle Rent', 'EXPENSE', NULL::text, FALSE),
    ('6280', 'Retread Plant Telephone', 'EXPENSE', NULL::text, FALSE),
    ('6290', 'Retread Plant Travel & Entertainment', 'EXPENSE', NULL::text, FALSE),
    ('6300', 'Retread Plant Fire Insurance', 'EXPENSE', NULL::text, FALSE),
    ('6310', 'Retread Plant Theft Insurance', 'EXPENSE', NULL::text, FALSE),
    ('6320', 'Retread Plant Liability Insurance', 'EXPENSE', NULL::text, FALSE),
    ('6330', 'Retread Plant Property Taxes', 'EXPENSE', NULL::text, FALSE),
    ('6340', 'Retread Shop Consumables', 'EXPENSE', NULL::text, FALSE),
    ('6360', 'Retread Plant Miscellaneous Supplies', 'EXPENSE', NULL::text, FALSE),
    ('6370', 'Retread Plant Office Supplies', 'EXPENSE', NULL::text, FALSE),
    ('6400', 'Retread Plant Utilities', 'EXPENSE', NULL::text, FALSE),
    ('6410', 'Retread Equipment Maintenance', 'EXPENSE', NULL::text, FALSE),
    ('6420', 'Retread Equipment Rental', 'EXPENSE', NULL::text, FALSE),
    ('6430', 'Retread Small Tools & Equipment', 'EXPENSE', NULL::text, FALSE),
    ('6460', 'Retread Other Shop Equipment Depreciation', 'EXPENSE', NULL::text, FALSE),
    ('6500', 'Retread Plant Administration Fees', 'EXPENSE', NULL::text, FALSE)
) AS t(code, name, type, subtype, reconcilable)
ON CONFLICT (tenant_id, account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable,
    activation_date = EXCLUDED.activation_date,
    modified_at = NOW(),
    modified_by = 'seed-generator';

-- Posting categories.
INSERT INTO posting_category (posting_category_id, category_name, description, is_active, created_at, created_by, modified_at, modified_by)
SELECT md5('accounting-template:01900000-0000-7000-8000-000000000000:CATEGORY:' || t.name)::uuid, t.name, t.description, TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator'
FROM (VALUES
    ('Order Revenue', 'ORDER_REVENUE'),
    ('PAYMENT_APPLICATION', 'AR cash receipt GL posting (Dr Undeposited Funds / Cr AR, decision D-3)'),
    ('SETTLEMENT', 'Batched processor settlement JE (decision D-13)'),
    ('SETTLEMENT_ADJUSTMENT', 'Settlement line write-off adjustment (decision D-14)'),
    ('BANK_RECONCILIATION', 'Bank reconciliation adjustment counter accounts (decision D-6)'),
    ('CUSTOMER_CREDIT_ISSUANCE', 'Overpayment credit issuance GL posting (Dr Undeposited Funds / Cr Customer Credit Liability, issue #975)'),
    ('CUSTOMER_CREDIT_APPLICATION', 'Customer credit applied to an invoice (Dr Customer Credit Liability / Cr Accounts Receivable, issue #992)'),
    ('CUSTOMER_CREDIT_REFUND', 'Customer credit refunded to the customer (Dr Customer Credit Liability / Cr Undeposited Funds, issue #992)'),
    ('INVENTORY_SHRINKAGE', 'Inventory scrap write-off GL posting (Dr Inventory Shrinkage / Cr Inventory, issue #1043)'),
    ('REGISTER_OVER_SHORT', 'Register-session drawer over/short variance (odoo-parity G3)'),
    ('INVENTORY_ADJUSTMENT', 'Inventory count / manual adjustment GL posting (loss Dr Shrinkage / Cr Inventory, gain Dr Inventory / Cr Shrinkage, #2191)'),
    ('INVENTORY_REVALUATION', 'Manual cost revaluation GL posting (write-up Dr Inventory / Cr COGS, write-down Dr COGS / Cr Inventory, #2193)'),
    ('INVOICE_REVENUE', 'Invoice revenue recognition on finalization (Dr AR / Cr Service Revenue / Cr Sales Tax Payable, #1843)')
) AS t(name, description)
ON CONFLICT (tenant_id, posting_category_id) DO UPDATE SET
    category_name = EXCLUDED.category_name,
    description = EXCLUDED.description,
    is_active = EXCLUDED.is_active,
    modified_at = NOW(),
    modified_by = 'seed-generator';

-- Mapping keys, found by name within their category at posting time.
INSERT INTO mapping_key (mapping_key_id, posting_category_id, key_name, description, is_active, created_at, created_by, modified_at, modified_by)
SELECT md5('accounting-template:01900000-0000-7000-8000-000000000000:MAPPING_KEY:' || t.category || '/' || t.key_name)::uuid, md5('accounting-template:01900000-0000-7000-8000-000000000000:CATEGORY:' || t.category)::uuid, t.key_name, t.description, TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator'
FROM (VALUES
    ('Order Revenue', 'DEFAULT', 'DEFAULT'),
    ('PAYMENT_APPLICATION', 'UNDEPOSITED_FUNDS', 'Debit side of AR cash receipt (decision D-3)'),
    ('PAYMENT_APPLICATION', 'ACCOUNTS_RECEIVABLE', 'Credit side of AR cash receipt'),
    ('SETTLEMENT', 'SETTLEMENT_CASH', 'Net bank payout (debit)'),
    ('SETTLEMENT', 'PROCESSOR_FEES', 'Processor fees (debit)'),
    ('SETTLEMENT', 'UNDEPOSITED_FUNDS', 'Matched receipts cleared (credit)'),
    ('SETTLEMENT', 'SETTLEMENT_SUSPENSE', 'Unmatched gross parked (credit)'),
    ('SETTLEMENT_ADJUSTMENT', 'SETTLEMENT_ADJUSTMENT', 'Write-off adjustment account'),
    ('BANK_RECONCILIATION', 'BANK_FEE', 'Bank service charge counter (expense)'),
    ('BANK_RECONCILIATION', 'NSF_FEE', 'Returned-item fee counter (expense)'),
    ('BANK_RECONCILIATION', 'INTEREST_EARNED', 'Interest income counter (revenue)'),
    ('BANK_RECONCILIATION', 'OTHER', 'Other reconciling adjustment counter (clearing)'),
    ('CUSTOMER_CREDIT_ISSUANCE', 'UNDEPOSITED_FUNDS', 'Debit side of customer credit issuance (overpayment cash received)'),
    ('CUSTOMER_CREDIT_ISSUANCE', 'CUSTOMER_CREDIT_LIABILITY', 'Credit side of customer credit issuance (obligation owed to customer)'),
    ('CUSTOMER_CREDIT_APPLICATION', 'CUSTOMER_CREDIT_LIABILITY', 'Debit side of a credit application (obligation discharged)'),
    ('CUSTOMER_CREDIT_APPLICATION', 'ACCOUNTS_RECEIVABLE', 'Credit side of a credit application (receivable settled)'),
    ('CUSTOMER_CREDIT_REFUND', 'CUSTOMER_CREDIT_LIABILITY', 'Debit side of a credit refund (obligation discharged)'),
    ('CUSTOMER_CREDIT_REFUND', 'UNDEPOSITED_FUNDS', 'Credit side of a credit refund (cash paid back to the customer)'),
    ('INVENTORY_SHRINKAGE', 'SHRINKAGE_EXPENSE', 'Debit side of a scrap write-off (shrinkage cost recognized)'),
    ('INVENTORY_SHRINKAGE', 'INVENTORY_ASSET', 'Credit side of a scrap write-off (stock value relieved)'),
    ('REGISTER_OVER_SHORT', 'CASH_SHORT', 'Cash shortage expense (debit on shortage)'),
    ('REGISTER_OVER_SHORT', 'CASH_OVER', 'Cash overage income (credit on overage)'),
    ('REGISTER_OVER_SHORT', 'CASH_CLEARING', 'Register cash clearing counter (the drawer)'),
    ('INVENTORY_ADJUSTMENT', 'ADJUSTMENT_LOSS', 'Debit side of an adjustment loss (on-hand down; shrinkage cost recognized)'),
    ('INVENTORY_ADJUSTMENT', 'ADJUSTMENT_GAIN', 'Credit side of an adjustment gain (on-hand up; nets against shrinkage, decision D2)'),
    ('INVENTORY_ADJUSTMENT', 'INVENTORY_ASSET', 'Inventory asset side of an adjustment (credit on loss, debit on gain)'),
    ('INVENTORY_REVALUATION', 'INVENTORY_ASSET', 'Inventory asset side of a revaluation (debit on write-up, credit on write-down)'),
    ('INVENTORY_REVALUATION', 'REVALUATION_OFFSET', 'Counter side of a revaluation (credit on write-up, debit on write-down; decision D7 final: 5000 COGS)'),
    ('INVOICE_REVENUE', 'ACCOUNTS_RECEIVABLE', 'Debit side of invoice revenue recognition (the receivable)'),
    ('INVOICE_REVENUE', 'SERVICE_REVENUE', 'Credit side of invoice revenue recognition (total - tax)'),
    ('INVOICE_REVENUE', 'SALES_TAX_PAYABLE', 'Credit side of invoice revenue recognition (tax collected)')
) AS t(category, key_name, description)
ON CONFLICT (tenant_id, mapping_key_id) DO UPDATE SET
    posting_category_id = EXCLUDED.posting_category_id,
    key_name = EXCLUDED.key_name,
    description = EXCLUDED.description,
    is_active = EXCLUDED.is_active,
    modified_at = NOW(),
    modified_by = 'seed-generator';

-- GL mappings: one per mapping key, without dimensions, effective from the template's date.
INSERT INTO gl_mapping (gl_mapping_id, source_system, external_code, posting_category_id, mapping_key_id, gl_account_id, effective_start_date, created_at, created_by)
SELECT md5('accounting-template:01900000-0000-7000-8000-000000000000:GL_MAPPING:' || t.category || '/' || t.key_name)::uuid, t.source_system, t.external_code, md5('accounting-template:01900000-0000-7000-8000-000000000000:CATEGORY:' || t.category)::uuid, md5('accounting-template:01900000-0000-7000-8000-000000000000:MAPPING_KEY:' || t.category || '/' || t.key_name)::uuid, md5('accounting-template:01900000-0000-7000-8000-000000000000:ACCOUNT:' || t.account_code)::uuid, TIMESTAMP '2020-01-01 00:00:00', NOW(), 'seed-generator'
FROM (VALUES
    ('Order Revenue', 'DEFAULT', 'ORDER', 'ORDER_COMPLETED', '4000'),
    ('PAYMENT_APPLICATION', 'UNDEPOSITED_FUNDS', 'ACCOUNTING', 'PAYMENT_APPLICATION_UNDEPOSITED_FUNDS', '1090'),
    ('PAYMENT_APPLICATION', 'ACCOUNTS_RECEIVABLE', 'ACCOUNTING', 'PAYMENT_APPLICATION_ACCOUNTS_RECEIVABLE', '1200'),
    ('SETTLEMENT', 'SETTLEMENT_CASH', 'ACCOUNTING', 'SETTLEMENT_CASH', '1000'),
    ('SETTLEMENT', 'PROCESSOR_FEES', 'ACCOUNTING', 'SETTLEMENT_PROCESSOR_FEES', '6000'),
    ('SETTLEMENT', 'UNDEPOSITED_FUNDS', 'ACCOUNTING', 'SETTLEMENT_UNDEPOSITED_FUNDS', '1090'),
    ('SETTLEMENT', 'SETTLEMENT_SUSPENSE', 'ACCOUNTING', 'SETTLEMENT_SUSPENSE', '2350'),
    ('SETTLEMENT_ADJUSTMENT', 'SETTLEMENT_ADJUSTMENT', 'ACCOUNTING', 'SETTLEMENT_ADJUSTMENT', '4900'),
    ('BANK_RECONCILIATION', 'BANK_FEE', 'ACCOUNTING', 'BANK_RECON_BANK_FEE', '6030'),
    ('BANK_RECONCILIATION', 'NSF_FEE', 'ACCOUNTING', 'BANK_RECON_NSF_FEE', '6020'),
    ('BANK_RECONCILIATION', 'INTEREST_EARNED', 'ACCOUNTING', 'BANK_RECON_INTEREST_EARNED', '4920'),
    ('BANK_RECONCILIATION', 'OTHER', 'ACCOUNTING', 'BANK_RECON_OTHER', '2360'),
    ('CUSTOMER_CREDIT_ISSUANCE', 'UNDEPOSITED_FUNDS', 'ACCOUNTING', 'CUSTOMER_CREDIT_ISSUANCE_UNDEPOSITED_FUNDS', '1090'),
    ('CUSTOMER_CREDIT_ISSUANCE', 'CUSTOMER_CREDIT_LIABILITY', 'ACCOUNTING', 'CUSTOMER_CREDIT_ISSUANCE_CUSTOMER_CREDIT_LIABILITY', '2300'),
    ('CUSTOMER_CREDIT_APPLICATION', 'CUSTOMER_CREDIT_LIABILITY', 'ACCOUNTING', 'CUSTOMER_CREDIT_APPLICATION_CUSTOMER_CREDIT_LIABILITY', '2300'),
    ('CUSTOMER_CREDIT_APPLICATION', 'ACCOUNTS_RECEIVABLE', 'ACCOUNTING', 'CUSTOMER_CREDIT_APPLICATION_ACCOUNTS_RECEIVABLE', '1200'),
    ('CUSTOMER_CREDIT_REFUND', 'CUSTOMER_CREDIT_LIABILITY', 'ACCOUNTING', 'CUSTOMER_CREDIT_REFUND_CUSTOMER_CREDIT_LIABILITY', '2300'),
    ('CUSTOMER_CREDIT_REFUND', 'UNDEPOSITED_FUNDS', 'ACCOUNTING', 'CUSTOMER_CREDIT_REFUND_UNDEPOSITED_FUNDS', '1090'),
    ('INVENTORY_SHRINKAGE', 'SHRINKAGE_EXPENSE', 'ACCOUNTING', 'INVENTORY_SHRINKAGE_SHRINKAGE_EXPENSE', '5100'),
    ('INVENTORY_SHRINKAGE', 'INVENTORY_ASSET', 'ACCOUNTING', 'INVENTORY_SHRINKAGE_INVENTORY_ASSET', '1300'),
    ('REGISTER_OVER_SHORT', 'CASH_SHORT', 'ACCOUNTING', 'REGISTER_OVER_SHORT_CASH_SHORT', '6115'),
    ('REGISTER_OVER_SHORT', 'CASH_OVER', 'ACCOUNTING', 'REGISTER_OVER_SHORT_CASH_OVER', '4930'),
    ('REGISTER_OVER_SHORT', 'CASH_CLEARING', 'ACCOUNTING', 'REGISTER_OVER_SHORT_CASH_CLEARING', '1095'),
    ('INVENTORY_ADJUSTMENT', 'ADJUSTMENT_LOSS', 'ACCOUNTING', 'INVENTORY_ADJUSTMENT_ADJUSTMENT_LOSS', '5100'),
    ('INVENTORY_ADJUSTMENT', 'ADJUSTMENT_GAIN', 'ACCOUNTING', 'INVENTORY_ADJUSTMENT_ADJUSTMENT_GAIN', '5100'),
    ('INVENTORY_ADJUSTMENT', 'INVENTORY_ASSET', 'ACCOUNTING', 'INVENTORY_ADJUSTMENT_INVENTORY_ASSET', '1300'),
    ('INVENTORY_REVALUATION', 'INVENTORY_ASSET', 'ACCOUNTING', 'INVENTORY_REVALUATION_INVENTORY_ASSET', '1300'),
    ('INVENTORY_REVALUATION', 'REVALUATION_OFFSET', 'ACCOUNTING', 'INVENTORY_REVALUATION_REVALUATION_OFFSET', '5000'),
    ('INVOICE_REVENUE', 'ACCOUNTS_RECEIVABLE', 'ACCOUNTING', 'INVOICE_REVENUE_ACCOUNTS_RECEIVABLE', '1200'),
    ('INVOICE_REVENUE', 'SERVICE_REVENUE', 'ACCOUNTING', 'INVOICE_REVENUE_SERVICE_REVENUE', '4000'),
    ('INVOICE_REVENUE', 'SALES_TAX_PAYABLE', 'ACCOUNTING', 'INVOICE_REVENUE_SALES_TAX_PAYABLE', '2200')
) AS t(category, key_name, source_system, external_code, account_code)
ON CONFLICT (tenant_id, gl_mapping_id) DO UPDATE SET
    source_system = EXCLUDED.source_system,
    external_code = EXCLUDED.external_code,
    posting_category_id = EXCLUDED.posting_category_id,
    mapping_key_id = EXCLUDED.mapping_key_id,
    gl_account_id = EXCLUDED.gl_account_id,
    effective_start_date = EXCLUDED.effective_start_date,
    effective_end_date = EXCLUDED.effective_end_date,
    dimensions = EXCLUDED.dimensions,
    created_by = 'seed-generator';

-- Default GL mappings, one per event type. organization_id is never populated (ADR-0062 §4).
INSERT INTO default_gl_mapping (mapping_id, event_type, organization_id, debit_account_id, credit_account_id, description, active, created_at, created_by, modified_at, modified_by)
SELECT md5('accounting-template:01900000-0000-7000-8000-000000000000:DEFAULT_GL_MAPPING:' || t.event_type)::uuid, t.event_type, NULL, md5('accounting-template:01900000-0000-7000-8000-000000000000:ACCOUNT:' || t.debit_code)::uuid, md5('accounting-template:01900000-0000-7000-8000-000000000000:ACCOUNT:' || t.credit_code)::uuid, t.description, TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator'
FROM (VALUES
    ('ORDER_CART_CREATE', '1200', '4000', 'ORDER_CART_CREATE default mapping')
) AS t(event_type, debit_code, credit_code, description)
ON CONFLICT (tenant_id, mapping_id) DO UPDATE SET
    event_type = EXCLUDED.event_type,
    organization_id = EXCLUDED.organization_id,
    debit_account_id = EXCLUDED.debit_account_id,
    credit_account_id = EXCLUDED.credit_account_id,
    description = EXCLUDED.description,
    active = EXCLUDED.active,
    modified_at = NOW(),
    modified_by = 'seed-generator';

-- Statement lines: the balance sheet and the income statement (CAP:550 S35, #2524; SPEC-accounting-
-- workspace §5.4, AW9). Stable line codes with plain-language descriptions; a code may carry more
-- than one account. An account with a posted balance and no line here lands on a computed line
-- (BS_OTHER_ASSETS, BS_OTHER_LIABILITIES, BS_OTHER_EQUITY, BS_PROFIT_NOT_YET_CLOSED, IS_OTHER_INCOME,
-- IS_OTHER_EXPENSES; a BANK_CASH account on BS_IN_THE_BANK), so no balance is left off a statement.
-- The line for 4000 used to be REVENUE; a tenant whose line is untouched since adoption follows the
-- recode to IS_SALES (S37's statement-line refresh). BS_KEPT_IN_DRAWERS (1080), BS_OWNER_EQUITY (3000)
-- and BS_OPENING_BALANCE_EQUITY (3900) are S15's; 1250 and 1260 are S32's.
INSERT INTO statement_line_mappings (mapping_id, gl_account_id, account_name, statement_type, statement_line_code, parent_line_code, line_description, display_order, operation)
SELECT md5('accounting-template:01900000-0000-7000-8000-000000000000:STATEMENT_LINE:' || t.statement_type || ':' || t.code)::uuid, md5('accounting-template:01900000-0000-7000-8000-000000000000:ACCOUNT:' || t.code)::uuid, t.code, t.statement_type, t.line_code, t.parent_line_code, t.line_description, t.display_order, t.operation
FROM (VALUES
    ('BALANCE_SHEET', '1000', 'BS_IN_THE_BANK', NULL::text, 'In the bank', 1, 'SUM'),
    ('BALANCE_SHEET', '1090', 'BS_WAITING_TO_BE_DEPOSITED', NULL::text, 'Waiting to be deposited', 2, 'SUM'),
    ('BALANCE_SHEET', '1095', 'BS_WAITING_TO_BE_DEPOSITED', NULL::text, 'Waiting to be deposited', 2, 'SUM'),
    ('BALANCE_SHEET', '1200', 'BS_CUSTOMERS_OWE_YOU', NULL::text, 'Money customers owe you', 3, 'SUM'),
    ('BALANCE_SHEET', '1300', 'BS_INVENTORY', NULL::text, 'Tires and parts on your shelves', 4, 'SUM'),
    ('BALANCE_SHEET', '2000', 'BS_BILLS_FROM_VENDORS', NULL::text, 'Bills from vendors', 5, 'SUM'),
    ('BALANCE_SHEET', '2200', 'BS_SALES_TAX_COLLECTED', NULL::text, 'Sales tax collected, not yet paid', 6, 'SUM'),
    ('BALANCE_SHEET', '2300', 'BS_CUSTOMER_CREDITS', NULL::text, 'Credits customers can still use', 7, 'SUM'),
    ('INCOME_STATEMENT', '4000', 'IS_SALES', NULL::text, 'Sales', 1, 'SUM'),
    ('INCOME_STATEMENT', '5000', 'IS_COST_OF_PARTS_SOLD', NULL::text, 'Cost of tires and parts sold', 2, 'SUM'),
    ('INCOME_STATEMENT', '6000', 'IS_CARD_PROCESSING_FEES', NULL::text, 'Card processing fees', 3, 'SUM')
) AS t(statement_type, code, line_code, parent_line_code, line_description, display_order, operation)
ON CONFLICT (tenant_id, mapping_id) DO UPDATE SET
    gl_account_id = EXCLUDED.gl_account_id,
    account_name = EXCLUDED.account_name,
    statement_type = EXCLUDED.statement_type,
    statement_line_code = EXCLUDED.statement_line_code,
    parent_line_code = EXCLUDED.parent_line_code,
    line_description = EXCLUDED.line_description,
    display_order = EXCLUDED.display_order,
    operation = EXCLUDED.operation;

-- Statement lines: the Labor & Overhead report (CAP-316), one leaf line per generic account above.
INSERT INTO statement_line_mappings (mapping_id, gl_account_id, account_name, statement_type, statement_line_code, parent_line_code, line_description, display_order, operation)
SELECT md5('accounting-template:01900000-0000-7000-8000-000000000000:STATEMENT_LINE:' || t.statement_type || ':' || t.code)::uuid, md5('accounting-template:01900000-0000-7000-8000-000000000000:ACCOUNT:' || t.code)::uuid, t.code, t.statement_type, t.line_code, t.parent_line_code, t.line_description, t.display_order, t.operation
FROM (VALUES
    ('LABOR_OVERHEAD', '6010', '1.1.1', '1.1', 'Hourly wages and bonuses', 1, 'SUM'),
    ('LABOR_OVERHEAD', '6015', '1.1.2', '1.1', 'Management salaries', 2, 'SUM'),
    ('LABOR_OVERHEAD', '6025', '1.2', NULL::text, 'Misc Labor (contract and temp production employees)', 3, 'SUM'),
    ('LABOR_OVERHEAD', '6110', '1.3.1', '1.3', 'FICA', 4, 'SUM'),
    ('LABOR_OVERHEAD', '6120', '1.3.2', '1.3', 'Fed Unemployment', 5, 'SUM'),
    ('LABOR_OVERHEAD', '6130', '1.3.3', '1.3', 'State Unemployment', 6, 'SUM'),
    ('LABOR_OVERHEAD', '6140', '1.3.4', '1.3', 'Medical/dental insurance, life, health, disability', 7, 'SUM'),
    ('LABOR_OVERHEAD', '6150', '1.3.5', '1.3', 'Retirement plan contributions', 8, 'SUM'),
    ('LABOR_OVERHEAD', '6160', '1.3.6', '1.3', 'Employee Insurance - workers'' comp', 9, 'SUM'),
    ('LABOR_OVERHEAD', '6170', '1.5', NULL::text, 'Uniforms rental / laundry', 10, 'SUM'),
    ('LABOR_OVERHEAD', '6200', '2.1.1', '2.1', 'Building depreciation', 11, 'SUM'),
    ('LABOR_OVERHEAD', '6210', '2.1.2', '2.1', 'Building maintenance', 12, 'SUM'),
    ('LABOR_OVERHEAD', '6220', '2.1.3', '2.1', 'Building rent', 13, 'SUM'),
    ('LABOR_OVERHEAD', '6230', '2.2', NULL::text, 'Training Costs', 14, 'SUM'),
    ('LABOR_OVERHEAD', '6240', '2.3', NULL::text, 'Employment advertising / recruiting costs', 15, 'SUM'),
    ('LABOR_OVERHEAD', '6250', '2.4.1', '2.4', 'Vehicle Gas & Oil', 16, 'SUM'),
    ('LABOR_OVERHEAD', '6255', '2.4.2', '2.4', 'Vehicle Maintenance', 17, 'SUM'),
    ('LABOR_OVERHEAD', '6260', '2.4.3', '2.4', 'Vehicle Taxes', 18, 'SUM'),
    ('LABOR_OVERHEAD', '6265', '2.4.4', '2.4', 'Vehicle Depreciation', 19, 'SUM'),
    ('LABOR_OVERHEAD', '6270', '2.4.5', '2.4', 'Vehicle Rent', 20, 'SUM'),
    ('LABOR_OVERHEAD', '6280', '2.5', NULL::text, 'Telephone', 21, 'SUM'),
    ('LABOR_OVERHEAD', '6290', '2.6', NULL::text, 'Travel and Entertainment', 22, 'SUM'),
    ('LABOR_OVERHEAD', '6300', '2.7.1', '2.7', 'Fire insurance', 23, 'SUM'),
    ('LABOR_OVERHEAD', '6310', '2.7.2', '2.7', 'Theft insurance', 24, 'SUM'),
    ('LABOR_OVERHEAD', '6320', '2.7.3', '2.7', 'Liability insurance', 25, 'SUM'),
    ('LABOR_OVERHEAD', '6330', '2.8', NULL::text, 'Property Taxes', 26, 'SUM'),
    ('LABOR_OVERHEAD', '6340', '2.9.1', '2.9', 'Shop Consumables (rasps, grinding wheels, brushes)', 27, 'SUM'),
    ('LABOR_OVERHEAD', '6360', '2.9.3', '2.9', 'Miscellaneous supplies', 29, 'SUM'),
    ('LABOR_OVERHEAD', '6370', '2.9.4', '2.9', 'Office supplies', 30, 'SUM'),
    ('LABOR_OVERHEAD', '6400', '2.10', NULL::text, 'Utilities - gas, electric, water', 31, 'SUM'),
    ('LABOR_OVERHEAD', '6410', '2.11.1', '2.11', 'Equipment maintenance', 32, 'SUM'),
    ('LABOR_OVERHEAD', '6420', '2.11.2', '2.11', 'Equipment rental', 33, 'SUM'),
    ('LABOR_OVERHEAD', '6430', '2.11.3', '2.11', 'Small tools and equipment', 34, 'SUM'),
    ('LABOR_OVERHEAD', '6460', '2.11.5', '2.11', 'Depreciation - Other shop equipment', 36, 'SUM'),
    ('LABOR_OVERHEAD', '6500', '2.12', NULL::text, 'Administration fees', 38, 'SUM')
) AS t(statement_type, code, line_code, parent_line_code, line_description, display_order, operation)
ON CONFLICT (tenant_id, mapping_id) DO UPDATE SET
    gl_account_id = EXCLUDED.gl_account_id,
    account_name = EXCLUDED.account_name,
    statement_type = EXCLUDED.statement_type,
    statement_line_code = EXCLUDED.statement_line_code,
    parent_line_code = EXCLUDED.parent_line_code,
    line_description = EXCLUDED.line_description,
    display_order = EXCLUDED.display_order,
    operation = EXCLUDED.operation;

-- ============================================================================
-- Retread-plant add-on (AW30; SPEC-accounting-workspace §4.6 "Retread add-on").
-- Opt-in: a tenant receives these only after a CONTROLLER or ADMIN turns the add-on on
-- (PUT /v1/accounting/tenant-template/add-ons/retread-plant). The account codes are listed in
-- RetreadPlantAddOnSource; LaborOverheadMappingSeedTest fails when the two disagree. 6900 becomes
-- 4940 with S15's renumbering.
-- ============================================================================

-- Retread add-on: GL accounts.
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, account_subtype, reconcilable, activation_date, version, created_at, created_by, modified_at, modified_by)
SELECT md5('accounting-template:01900000-0000-7000-8000-000000000000:ACCOUNT:' || t.code)::uuid, t.code, t.name, t.type, t.subtype, t.reconcilable, TIMESTAMP '2020-01-01 00:00:00', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'
FROM (VALUES
    ('6350', 'Retread Curing Consumables', 'EXPENSE', NULL::text, FALSE),
    ('6450', 'Retread MRT Equipment Depreciation (USD)', 'EXPENSE', NULL::text, FALSE),
    ('6470', 'MRTI Equipment Leases & Software', 'EXPENSE', NULL::text, FALSE),
    ('6510', 'Retread Inventory Charge', 'EXPENSE', NULL::text, FALSE),
    ('6520', 'Casings Scrapped In Production', 'EXPENSE', NULL::text, FALSE),
    ('6530', 'Retread Production Adjustments', 'EXPENSE', NULL::text, FALSE),
    ('6900', 'Rubber Dust Sales Income', 'REVENUE', NULL::text, FALSE)
) AS t(code, name, type, subtype, reconcilable)
ON CONFLICT (tenant_id, account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    account_subtype = EXCLUDED.account_subtype,
    reconcilable = EXCLUDED.reconcilable,
    activation_date = EXCLUDED.activation_date,
    modified_at = NOW(),
    modified_by = 'seed-generator';

-- Retread add-on: Labor & Overhead lines.
INSERT INTO statement_line_mappings (mapping_id, gl_account_id, account_name, statement_type, statement_line_code, parent_line_code, line_description, display_order, operation)
SELECT md5('accounting-template:01900000-0000-7000-8000-000000000000:STATEMENT_LINE:' || t.statement_type || ':' || t.code)::uuid, md5('accounting-template:01900000-0000-7000-8000-000000000000:ACCOUNT:' || t.code)::uuid, t.code, t.statement_type, t.line_code, t.parent_line_code, t.line_description, t.display_order, t.operation
FROM (VALUES
    ('LABOR_OVERHEAD', '6350', '2.9.2', '2.9', 'Curing Consumables (envelopes, lube, wicks, poly)', 28, 'SUM'),
    ('LABOR_OVERHEAD', '6450', '2.11.4', '2.11', 'Depreciation - MRT process equipment ($US only)', 35, 'SUM'),
    ('LABOR_OVERHEAD', '6470', '2.11.6', '2.11', 'MRTI equipment leases or rent', 37, 'SUM'),
    ('LABOR_OVERHEAD', '6510', '2.13', NULL::text, 'Inventory charge', 39, 'SUM'),
    ('LABOR_OVERHEAD', '6900', '2.14', NULL::text, 'Income from rubber dust sales', 40, 'SUM'),
    ('LABOR_OVERHEAD', '6520', '2.15.1', '2.15', 'Casings scrapped in production', 41, 'SUM'),
    ('LABOR_OVERHEAD', '6530', '2.15.2', '2.15', 'Adjustments (customer returns)', 42, 'SUM')
) AS t(statement_type, code, line_code, parent_line_code, line_description, display_order, operation)
ON CONFLICT (tenant_id, mapping_id) DO UPDATE SET
    gl_account_id = EXCLUDED.gl_account_id,
    account_name = EXCLUDED.account_name,
    statement_type = EXCLUDED.statement_type,
    statement_line_code = EXCLUDED.statement_line_code,
    parent_line_code = EXCLUDED.parent_line_code,
    line_description = EXCLUDED.line_description,
    display_order = EXCLUDED.display_order,
    operation = EXCLUDED.operation;
