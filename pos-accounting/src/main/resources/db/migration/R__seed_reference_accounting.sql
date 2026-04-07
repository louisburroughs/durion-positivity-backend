-- Repeatable seed migration for accounting reference data.
-- Source: durion/scripts/seed-generator/generated-seed-sql/005_accounting.sql
SET TIME ZONE 'UTC';


-- GL accounts
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, created_at, created_by)
VALUES ('b8798348-d3be-9582-7a6d-883ae3e64e66'::uuid, '4000', 'Service Revenue', 'REVENUE', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET account_name = EXCLUDED.account_name;
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, created_at, created_by)
VALUES ('0f12890f-383d-b449-b555-bd4b37bf1f44'::uuid, '1200', 'Accounts Receivable', 'ASSET', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET account_name = EXCLUDED.account_name;

-- Posting categories
INSERT INTO posting_category (posting_category_id, category_name, description, is_active, created_at, created_by)
VALUES ('70eb38c4-cf6a-992a-81c3-2a4c958a458a'::uuid, 'Order Revenue', 'ORDER_REVENUE', TRUE, NOW(), 'seed-generator')
ON CONFLICT (posting_category_id) DO NOTHING;

-- Mapping keys
INSERT INTO mapping_key (mapping_key_id, posting_category_id, key_name, description, is_active, created_at, created_by)
VALUES ('05b9e38b-003d-5a02-ec1b-db48542ccc12'::uuid, '70eb38c4-cf6a-992a-81c3-2a4c958a458a'::uuid, 'DEFAULT', 'DEFAULT', TRUE, NOW(), 'seed-generator')
ON CONFLICT (mapping_key_id) DO NOTHING;

-- GL mappings
INSERT INTO gl_mapping (gl_mapping_id, source_system, external_code, posting_category_id, mapping_key_id, gl_account_id, effective_start_date, created_at, created_by)
VALUES ('c3d2b7cf-a895-077a-2a37-48115a2b7c22'::uuid, 'ORDER', 'ORDER_COMPLETED', '70eb38c4-cf6a-992a-81c3-2a4c958a458a'::uuid, '05b9e38b-003d-5a02-ec1b-db48542ccc12'::uuid, 'b8798348-d3be-9582-7a6d-883ae3e64e66'::uuid, NOW(), NOW(), 'seed-generator')
ON CONFLICT (gl_mapping_id) DO NOTHING;

-- Default GL mappings
INSERT INTO default_gl_mapping (mapping_id, event_type, organization_id, debit_account_id, credit_account_id, description, active, created_at, created_by, modified_at, modified_by)
VALUES ('9a2f5863-9a58-c159-0a0f-85ff599dd791'::uuid, 'ORDER_CART_CREATE', NULL, '0f12890f-383d-b449-b555-bd4b37bf1f44'::uuid, 'b8798348-d3be-9582-7a6d-883ae3e64e66'::uuid, 'ORDER_CART_CREATE default mapping', TRUE, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (mapping_id) DO NOTHING;

-- Statement line mappings
INSERT INTO statement_line_mappings (mapping_id, gl_account_id, account_name, statement_type, statement_line_code, line_description, display_order, operation)
VALUES ('3286527a-37f8-083e-c1d0-b8af8bab8afa'::uuid, 'b8798348-d3be-9582-7a6d-883ae3e64e66'::uuid, '4000', 'INCOME_STATEMENT', 'REVENUE', 'REVENUE', 1, 'SUM')
ON CONFLICT (mapping_id) DO NOTHING;
