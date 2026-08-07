-- Issue #731 (CAP-316 follow-up): complete the Labor & Overhead report-line -> GL-account mapping
-- and add location currency/FX enrichment master data.
--
-- 1. statement_line_mappings gains a nullable location_id: a NULL row is the global default for a
--    line; rows carrying a location_id override the global rows for that line at that location
--    (persisted, per-location-overridable master data — the decision recorded on #731).
-- 2. Every canonical CAP-316 leaf line (Labor 1.1.1–1.5, Overhead 2.1.1–2.15.2) now has an
--    authoritative global mapping against the retread-plant chart of accounts (6xxx expense series;
--    2.14 rubber-dust income stays on revenue account 6900). The five representative V3 rows are
--    re-asserted here with their canonical display order.
-- 3. accounting_location_profile / accounting_location_fx_rate hold the location dimension used to
--    resolve locationLabel + currency and the per-fiscal-year FX rates (localCurrencyPerUsd,
--    averageRate). Locations absent from these tables are treated as US plants (USD, 1.00).
--
-- FLYWAY COLLISION NOTE: numbered V25 as the next contiguous version after V24. Unmerged
-- accounting branches may also claim a V2x; renumber on rebase if one merges first.

-- ---------------------------------------------------------------- 1. location override column
ALTER TABLE statement_line_mappings
    ADD COLUMN location_id character varying(100);

CREATE INDEX idx_statement_line_mapping_line_location
    ON statement_line_mappings (statement_type, statement_line_code, location_id);

-- ---------------------------------------------------------------- 3. location enrichment tables
CREATE TABLE accounting_location_profile (
    profile_id uuid NOT NULL,
    location_code character varying(100) NOT NULL,
    location_label character varying(255) NOT NULL,
    currency_code character varying(3) NOT NULL,
    version integer,
    created_at timestamp(6) with time zone NOT NULL,
    modified_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT accounting_location_profile_pkey PRIMARY KEY (profile_id),
    CONSTRAINT uq_accounting_location_profile_code UNIQUE (location_code)
);

CREATE TABLE accounting_location_fx_rate (
    fx_rate_id uuid NOT NULL,
    location_code character varying(100) NOT NULL,
    fiscal_year integer NOT NULL,
    local_currency_per_usd numeric(12, 6) NOT NULL,
    average_rate numeric(12, 6) NOT NULL,
    version integer,
    created_at timestamp(6) with time zone NOT NULL,
    modified_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT accounting_location_fx_rate_pkey PRIMARY KEY (fx_rate_id),
    CONSTRAINT uq_accounting_location_fx_rate UNIQUE (location_code, fiscal_year),
    CONSTRAINT accounting_location_fx_rate_per_usd_check CHECK (local_currency_per_usd > 0),
    CONSTRAINT accounting_location_fx_rate_average_check CHECK (average_rate > 0)
);

-- ---------------------------------------------------------------- 2. full chart + mapping seed
-- Retread-plant chart of accounts (existing: 6010, 6110, 6400, 6450, 6900 from V3).
INSERT INTO gl_account (gl_account_id, account_code, account_name, account_type, version, created_at, created_by, modified_at, modified_by)
VALUES
    ('a1000000-0000-7000-8000-000000000006'::uuid, '6015', 'Retread Plant Management Salaries', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000007'::uuid, '6025', 'Retread Plant Contract & Temp Labor', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000008'::uuid, '6120', 'Retread Plant Federal Unemployment Tax', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000009'::uuid, '6130', 'Retread Plant State Unemployment Tax', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000010'::uuid, '6140', 'Retread Plant Medical & Life Insurance', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000011'::uuid, '6150', 'Retread Plant Retirement Contributions', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000012'::uuid, '6160', 'Retread Plant Workers Comp Insurance', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000013'::uuid, '6170', 'Retread Plant Uniforms & Laundry', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000014'::uuid, '6200', 'Retread Building Depreciation', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000015'::uuid, '6210', 'Retread Building Maintenance', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000016'::uuid, '6220', 'Retread Building Rent', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000017'::uuid, '6230', 'Retread Plant Training Costs', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000018'::uuid, '6240', 'Retread Plant Recruiting & Employment Advertising', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000019'::uuid, '6250', 'Retread Plant Vehicle Gas & Oil', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000020'::uuid, '6255', 'Retread Plant Vehicle Maintenance', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000021'::uuid, '6260', 'Retread Plant Vehicle Taxes', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000022'::uuid, '6265', 'Retread Plant Vehicle Depreciation', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000023'::uuid, '6270', 'Retread Plant Vehicle Rent', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000024'::uuid, '6280', 'Retread Plant Telephone', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000025'::uuid, '6290', 'Retread Plant Travel & Entertainment', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000026'::uuid, '6300', 'Retread Plant Fire Insurance', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000027'::uuid, '6310', 'Retread Plant Theft Insurance', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000028'::uuid, '6320', 'Retread Plant Liability Insurance', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000029'::uuid, '6330', 'Retread Plant Property Taxes', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000030'::uuid, '6340', 'Retread Shop Consumables', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000031'::uuid, '6350', 'Retread Curing Consumables', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000032'::uuid, '6360', 'Retread Plant Miscellaneous Supplies', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000033'::uuid, '6370', 'Retread Plant Office Supplies', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000034'::uuid, '6410', 'Retread Equipment Maintenance', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000035'::uuid, '6420', 'Retread Equipment Rental', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000036'::uuid, '6430', 'Retread Small Tools & Equipment', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000037'::uuid, '6460', 'Retread Other Shop Equipment Depreciation', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000038'::uuid, '6470', 'MRTI Equipment Leases & Software', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000039'::uuid, '6500', 'Retread Plant Administration Fees', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000040'::uuid, '6510', 'Retread Inventory Charge', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000041'::uuid, '6520', 'Casings Scrapped In Production', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator'),
    ('a1000000-0000-7000-8000-000000000042'::uuid, '6530', 'Retread Production Adjustments', 'EXPENSE', 0, NOW(), 'seed-generator', NOW(), 'seed-generator')
ON CONFLICT (account_code) DO UPDATE SET
    account_name = EXCLUDED.account_name,
    account_type = EXCLUDED.account_type,
    modified_at = NOW(),
    modified_by = 'seed-generator';

-- Global (location_id NULL) mapping for every canonical leaf line, canonical display order.
-- Rows b...01–05 re-assert the V3 representative rows with their canonical positions.
INSERT INTO statement_line_mappings (mapping_id, gl_account_id, account_name, statement_type, statement_line_code, parent_line_code, line_description, display_order, operation)
VALUES
    ('b1000000-0000-7000-8000-000000000001'::uuid, 'a1000000-0000-7000-8000-000000000001'::uuid, '6010', 'LABOR_OVERHEAD', '1.1.1', '1.1', 'Hourly wages and bonuses', 1, 'SUM'),
    ('b1000000-0000-7000-8000-000000000006'::uuid, 'a1000000-0000-7000-8000-000000000006'::uuid, '6015', 'LABOR_OVERHEAD', '1.1.2', '1.1', 'Management salaries', 2, 'SUM'),
    ('b1000000-0000-7000-8000-000000000007'::uuid, 'a1000000-0000-7000-8000-000000000007'::uuid, '6025', 'LABOR_OVERHEAD', '1.2', NULL, 'Misc Labor (contract and temp production employees)', 3, 'SUM'),
    ('b1000000-0000-7000-8000-000000000002'::uuid, 'a1000000-0000-7000-8000-000000000002'::uuid, '6110', 'LABOR_OVERHEAD', '1.3.1', '1.3', 'FICA', 4, 'SUM'),
    ('b1000000-0000-7000-8000-000000000008'::uuid, 'a1000000-0000-7000-8000-000000000008'::uuid, '6120', 'LABOR_OVERHEAD', '1.3.2', '1.3', 'Fed Unemployment', 5, 'SUM'),
    ('b1000000-0000-7000-8000-000000000009'::uuid, 'a1000000-0000-7000-8000-000000000009'::uuid, '6130', 'LABOR_OVERHEAD', '1.3.3', '1.3', 'State Unemployment', 6, 'SUM'),
    ('b1000000-0000-7000-8000-000000000010'::uuid, 'a1000000-0000-7000-8000-000000000010'::uuid, '6140', 'LABOR_OVERHEAD', '1.3.4', '1.3', 'Medical/dental insurance, life, health, disability', 7, 'SUM'),
    ('b1000000-0000-7000-8000-000000000011'::uuid, 'a1000000-0000-7000-8000-000000000011'::uuid, '6150', 'LABOR_OVERHEAD', '1.3.5', '1.3', 'Retirement plan contributions', 8, 'SUM'),
    ('b1000000-0000-7000-8000-000000000012'::uuid, 'a1000000-0000-7000-8000-000000000012'::uuid, '6160', 'LABOR_OVERHEAD', '1.3.6', '1.3', 'Employee Insurance - workers'' comp', 9, 'SUM'),
    ('b1000000-0000-7000-8000-000000000013'::uuid, 'a1000000-0000-7000-8000-000000000013'::uuid, '6170', 'LABOR_OVERHEAD', '1.5', NULL, 'Uniforms rental / laundry', 10, 'SUM'),
    ('b1000000-0000-7000-8000-000000000014'::uuid, 'a1000000-0000-7000-8000-000000000014'::uuid, '6200', 'LABOR_OVERHEAD', '2.1.1', '2.1', 'Building depreciation', 11, 'SUM'),
    ('b1000000-0000-7000-8000-000000000015'::uuid, 'a1000000-0000-7000-8000-000000000015'::uuid, '6210', 'LABOR_OVERHEAD', '2.1.2', '2.1', 'Building maintenance', 12, 'SUM'),
    ('b1000000-0000-7000-8000-000000000016'::uuid, 'a1000000-0000-7000-8000-000000000016'::uuid, '6220', 'LABOR_OVERHEAD', '2.1.3', '2.1', 'Building rent', 13, 'SUM'),
    ('b1000000-0000-7000-8000-000000000017'::uuid, 'a1000000-0000-7000-8000-000000000017'::uuid, '6230', 'LABOR_OVERHEAD', '2.2', NULL, 'Training Costs', 14, 'SUM'),
    ('b1000000-0000-7000-8000-000000000018'::uuid, 'a1000000-0000-7000-8000-000000000018'::uuid, '6240', 'LABOR_OVERHEAD', '2.3', NULL, 'Employment advertising / recruiting costs', 15, 'SUM'),
    ('b1000000-0000-7000-8000-000000000019'::uuid, 'a1000000-0000-7000-8000-000000000019'::uuid, '6250', 'LABOR_OVERHEAD', '2.4.1', '2.4', 'Vehicle Gas & Oil', 16, 'SUM'),
    ('b1000000-0000-7000-8000-000000000020'::uuid, 'a1000000-0000-7000-8000-000000000020'::uuid, '6255', 'LABOR_OVERHEAD', '2.4.2', '2.4', 'Vehicle Maintenance', 17, 'SUM'),
    ('b1000000-0000-7000-8000-000000000021'::uuid, 'a1000000-0000-7000-8000-000000000021'::uuid, '6260', 'LABOR_OVERHEAD', '2.4.3', '2.4', 'Vehicle Taxes', 18, 'SUM'),
    ('b1000000-0000-7000-8000-000000000022'::uuid, 'a1000000-0000-7000-8000-000000000022'::uuid, '6265', 'LABOR_OVERHEAD', '2.4.4', '2.4', 'Vehicle Depreciation', 19, 'SUM'),
    ('b1000000-0000-7000-8000-000000000023'::uuid, 'a1000000-0000-7000-8000-000000000023'::uuid, '6270', 'LABOR_OVERHEAD', '2.4.5', '2.4', 'Vehicle Rent', 20, 'SUM'),
    ('b1000000-0000-7000-8000-000000000024'::uuid, 'a1000000-0000-7000-8000-000000000024'::uuid, '6280', 'LABOR_OVERHEAD', '2.5', NULL, 'Telephone', 21, 'SUM'),
    ('b1000000-0000-7000-8000-000000000025'::uuid, 'a1000000-0000-7000-8000-000000000025'::uuid, '6290', 'LABOR_OVERHEAD', '2.6', NULL, 'Travel and Entertainment', 22, 'SUM'),
    ('b1000000-0000-7000-8000-000000000026'::uuid, 'a1000000-0000-7000-8000-000000000026'::uuid, '6300', 'LABOR_OVERHEAD', '2.7.1', '2.7', 'Fire insurance', 23, 'SUM'),
    ('b1000000-0000-7000-8000-000000000027'::uuid, 'a1000000-0000-7000-8000-000000000027'::uuid, '6310', 'LABOR_OVERHEAD', '2.7.2', '2.7', 'Theft insurance', 24, 'SUM'),
    ('b1000000-0000-7000-8000-000000000028'::uuid, 'a1000000-0000-7000-8000-000000000028'::uuid, '6320', 'LABOR_OVERHEAD', '2.7.3', '2.7', 'Liability insurance', 25, 'SUM'),
    ('b1000000-0000-7000-8000-000000000029'::uuid, 'a1000000-0000-7000-8000-000000000029'::uuid, '6330', 'LABOR_OVERHEAD', '2.8', NULL, 'Property Taxes', 26, 'SUM'),
    ('b1000000-0000-7000-8000-000000000030'::uuid, 'a1000000-0000-7000-8000-000000000030'::uuid, '6340', 'LABOR_OVERHEAD', '2.9.1', '2.9', 'Shop Consumables (rasps, grinding wheels, brushes)', 27, 'SUM'),
    ('b1000000-0000-7000-8000-000000000031'::uuid, 'a1000000-0000-7000-8000-000000000031'::uuid, '6350', 'LABOR_OVERHEAD', '2.9.2', '2.9', 'Curing Consumables (envelopes, lube, wicks, poly)', 28, 'SUM'),
    ('b1000000-0000-7000-8000-000000000032'::uuid, 'a1000000-0000-7000-8000-000000000032'::uuid, '6360', 'LABOR_OVERHEAD', '2.9.3', '2.9', 'Miscellaneous supplies', 29, 'SUM'),
    ('b1000000-0000-7000-8000-000000000033'::uuid, 'a1000000-0000-7000-8000-000000000033'::uuid, '6370', 'LABOR_OVERHEAD', '2.9.4', '2.9', 'Office supplies', 30, 'SUM'),
    ('b1000000-0000-7000-8000-000000000003'::uuid, 'a1000000-0000-7000-8000-000000000003'::uuid, '6400', 'LABOR_OVERHEAD', '2.10', NULL, 'Utilities - gas, electric, water', 31, 'SUM'),
    ('b1000000-0000-7000-8000-000000000034'::uuid, 'a1000000-0000-7000-8000-000000000034'::uuid, '6410', 'LABOR_OVERHEAD', '2.11.1', '2.11', 'Equipment maintenance', 32, 'SUM'),
    ('b1000000-0000-7000-8000-000000000035'::uuid, 'a1000000-0000-7000-8000-000000000035'::uuid, '6420', 'LABOR_OVERHEAD', '2.11.2', '2.11', 'Equipment rental', 33, 'SUM'),
    ('b1000000-0000-7000-8000-000000000036'::uuid, 'a1000000-0000-7000-8000-000000000036'::uuid, '6430', 'LABOR_OVERHEAD', '2.11.3', '2.11', 'Small tools and equipment', 34, 'SUM'),
    ('b1000000-0000-7000-8000-000000000004'::uuid, 'a1000000-0000-7000-8000-000000000004'::uuid, '6450', 'LABOR_OVERHEAD', '2.11.4', '2.11', 'Depreciation - MRT process equipment ($US only)', 35, 'SUM'),
    ('b1000000-0000-7000-8000-000000000037'::uuid, 'a1000000-0000-7000-8000-000000000037'::uuid, '6460', 'LABOR_OVERHEAD', '2.11.5', '2.11', 'Depreciation - Other shop equipment', 36, 'SUM'),
    ('b1000000-0000-7000-8000-000000000038'::uuid, 'a1000000-0000-7000-8000-000000000038'::uuid, '6470', 'LABOR_OVERHEAD', '2.11.6', '2.11', 'MRTI equipment leases or rent', 37, 'SUM'),
    ('b1000000-0000-7000-8000-000000000039'::uuid, 'a1000000-0000-7000-8000-000000000039'::uuid, '6500', 'LABOR_OVERHEAD', '2.12', NULL, 'Administration fees', 38, 'SUM'),
    ('b1000000-0000-7000-8000-000000000040'::uuid, 'a1000000-0000-7000-8000-000000000040'::uuid, '6510', 'LABOR_OVERHEAD', '2.13', NULL, 'Inventory charge', 39, 'SUM'),
    ('b1000000-0000-7000-8000-000000000005'::uuid, 'a1000000-0000-7000-8000-000000000005'::uuid, '6900', 'LABOR_OVERHEAD', '2.14', NULL, 'Income from rubber dust sales', 40, 'SUM'),
    ('b1000000-0000-7000-8000-000000000041'::uuid, 'a1000000-0000-7000-8000-000000000041'::uuid, '6520', 'LABOR_OVERHEAD', '2.15.1', '2.15', 'Casings scrapped in production', 41, 'SUM'),
    ('b1000000-0000-7000-8000-000000000042'::uuid, 'a1000000-0000-7000-8000-000000000042'::uuid, '6530', 'LABOR_OVERHEAD', '2.15.2', '2.15', 'Adjustments (customer returns)', 42, 'SUM')
ON CONFLICT (mapping_id) DO UPDATE SET
    gl_account_id = EXCLUDED.gl_account_id,
    account_name = EXCLUDED.account_name,
    statement_type = EXCLUDED.statement_type,
    statement_line_code = EXCLUDED.statement_line_code,
    parent_line_code = EXCLUDED.parent_line_code,
    line_description = EXCLUDED.line_description,
    display_order = EXCLUDED.display_order,
    operation = EXCLUDED.operation;
