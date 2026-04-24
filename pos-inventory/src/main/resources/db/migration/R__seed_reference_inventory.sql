-- Repeatable seed migration for inventory reference data.
-- Source: durion/scripts/seed-generator/generated-seed-sql/004_catalog_pricing_inventory.sql
-- Notes:
-- - Includes only pos-inventory-owned tables.
-- - Adapted column names to current inventory Flyway schema.
SET TIME ZONE 'UTC';

-- Replenishment policies
INSERT INTO replenishment_policy (policy_id, location_id, itemsku, minimum_quantity, maximum_quantity, created_at, updated_at)
VALUES ('4db18cdb-755c-a13b-e253-201f79d997fe'::uuid, '96dd346a-047c-86f5-3c9a-7c8cac53da86'::uuid, 'OIL-5W30-5QT', 20, 40, NOW(), NOW())
ON CONFLICT (policy_id) DO NOTHING;

-- Putaway rules
INSERT INTO putaway_rule (rule_id, priority, criteria, destination_location_id, is_enabled, created_at, updated_at)
VALUES ('6f46541c-937d-397a-076f-63e092cabed6'::uuid, 1, '{"sku_prefix":"OIL-","location_id":"96dd346a-047c-86f5-3c9a-7c8cac53da86"}', '96dd346a-047c-86f5-3c9a-7c8cac53da86'::uuid, TRUE, NOW(), NOW())
ON CONFLICT (rule_id) DO NOTHING;

-- Approval thresholds
INSERT INTO approval_threshold_config (
    config_id,
    approval_tier,
    unit_variance_threshold,
    value_variance_threshold,
    percentage_variance_threshold,
    active,
    created_at,
    updated_at
)
VALUES ('36458d57-6d89-5f33-ab7b-cca72774fd21'::uuid, 'TIER_1_MANAGER', 0, 100, 0, TRUE, NOW(), NOW())
ON CONFLICT (config_id) DO NOTHING;

-- =============================================================================
-- INVENTORY LEDGER ENTRIES — initial GOODS_RECEIPT stock for CLT-MAIN-001
-- storage location UUID prefix: 01960004-0001-7000-8000-
-- Bins A-01..A-10: motor oils & fluids
-- Bins B-01..B-10: oil filters
-- Bins C-01..C-10: air/cabin/fuel filters
-- Bins D-01..D-10: spark plugs & wipers
-- Bins E-01..E-10: brakes, batteries & misc
-- Tire Rack A (...047): Michelin CrossClimate2 tires
-- Tire Rack B (...048): Michelin Defender T+H & Pilot Sport 4S
-- =============================================================================

-- Shelf A — Motor Oils & Fluids (Bins A-01..A-10)
INSERT INTO inventory_ledger_entry (ledger_entry_id, stock_item_id, location_id, event_type, change_in_quantity, quantity_after, unit_cost, unit_of_measure, "timestamp", created_at, updated_at, transaction_user_id, notes)
VALUES
  (md5('inv_seed:MOBI-120764')::uuid,  'MOBI-120764', '01960004-0001-7000-8000-000000000009'::uuid, 'GOODS_RECEIPT', 24, 24, 9.99,  'QT',  NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Mobil 1 5W-30 Full Synthetic Quart'),
  (md5('inv_seed:MOBI-14977')::uuid,   'MOBI-14977',  '01960004-0001-7000-8000-00000000000a'::uuid, 'GOODS_RECEIPT', 12, 12, 26.99, 'GAL', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Mobil 1 5W-30 Full Synthetic 5-Qt Jug'),
  (md5('inv_seed:VALV-787987')::uuid,  'VALV-787987', '01960004-0001-7000-8000-00000000000b'::uuid, 'GOODS_RECEIPT', 24, 24, 8.49,  'QT',  NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Valvoline Advanced Full Synthetic 5W-30'),
  (md5('inv_seed:VALV-782253')::uuid,  'VALV-782253', '01960004-0001-7000-8000-00000000000c'::uuid, 'GOODS_RECEIPT', 12, 12, 24.99, 'GAL', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Valvoline Advanced Full Synthetic 5W-30 5-Qt'),
  (md5('inv_seed:MOBI-72206')::uuid,   'MOBI-72206',  '01960004-0001-7000-8000-00000000000d'::uuid, 'GOODS_RECEIPT', 24, 24, 8.99,  'QT',  NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Mobil Super 5000 10W-30'),
  (md5('inv_seed:PRES-AF2100')::uuid,  'PRES-AF2100', '01960004-0001-7000-8000-00000000000e'::uuid, 'GOODS_RECEIPT', 12, 12, 11.49, 'GAL', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Prestone 50/50 Antifreeze/Coolant 1-Gal'),
  (md5('inv_seed:PRES-AF6100')::uuid,  'PRES-AF6100', '01960004-0001-7000-8000-00000000000f'::uuid, 'GOODS_RECEIPT', 6,  6,  18.99, 'GAL', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Prestone Extended Life Antifreeze 1-Gal'),
  (md5('inv_seed:VALV-ATF-ML-QT')::uuid, 'VALV-ATF-ML-QT', '01960004-0001-7000-8000-000000000010'::uuid, 'GOODS_RECEIPT', 12, 12, 7.99, 'QT', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Valvoline MaxLife ATF Quart'),
  (md5('inv_seed:PRES-AS800Y')::uuid,  'PRES-AS800Y', '01960004-0001-7000-8000-000000000011'::uuid, 'GOODS_RECEIPT', 6,  6,  4.99,  'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Prestone Power Steering Fluid 12-oz'),
  (md5('inv_seed:VALV-601458')::uuid,  'VALV-601458', '01960004-0001-7000-8000-000000000012'::uuid, 'GOODS_RECEIPT', 12, 12, 5.49,  'QT',  NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Valvoline Brake Fluid DOT 3')
ON CONFLICT (ledger_entry_id) DO NOTHING;

-- Shelf B — Oil Filters (Bins B-01..B-10)
INSERT INTO inventory_ledger_entry (ledger_entry_id, stock_item_id, location_id, event_type, change_in_quantity, quantity_after, unit_cost, unit_of_measure, "timestamp", created_at, updated_at, transaction_user_id, notes)
VALUES
  (md5('inv_seed:WIXF-XP57356')::uuid, 'WIXF-XP57356', '01960004-0001-7000-8000-000000000013'::uuid, 'GOODS_RECEIPT', 12, 12, 14.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: WIX XP Extended Life Oil Filter 57356XP'),
  (md5('inv_seed:WIXF-XP51394')::uuid, 'WIXF-XP51394', '01960004-0001-7000-8000-000000000014'::uuid, 'GOODS_RECEIPT', 12, 12, 14.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: WIX XP Extended Life Oil Filter 51394XP'),
  (md5('inv_seed:WIXF-57356')::uuid,   'WIXF-57356',   '01960004-0001-7000-8000-000000000015'::uuid, 'GOODS_RECEIPT', 12, 12, 8.99,  'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: WIX Standard Oil Filter 57356'),
  (md5('inv_seed:FRAM-XG7317')::uuid,  'FRAM-XG7317',  '01960004-0001-7000-8000-000000000016'::uuid, 'GOODS_RECEIPT', 12, 12, 13.49, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Fram Ultra Synthetic Oil Filter XG7317'),
  (md5('inv_seed:FRAM-PH7317')::uuid,  'FRAM-PH7317',  '01960004-0001-7000-8000-000000000017'::uuid, 'GOODS_RECEIPT', 12, 12, 7.99,  'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Fram Extra Guard Oil Filter PH7317'),
  (md5('inv_seed:PURO-PP10060')::uuid, 'PURO-PP10060', '01960004-0001-7000-8000-000000000018'::uuid, 'GOODS_RECEIPT', 12, 12, 9.49,  'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Purolator PureONE Oil Filter PL10060'),
  (md5('inv_seed:BSCH-OFP3400')::uuid, 'BSCH-OFP3400', '01960004-0001-7000-8000-000000000019'::uuid, 'GOODS_RECEIPT', 12, 12, 11.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Bosch Premium FILTECH Oil Filter 3400'),
  (md5('inv_seed:ACDL-PF63E')::uuid,   'ACDL-PF63E',   '01960004-0001-7000-8000-00000000001a'::uuid, 'GOODS_RECEIPT', 12, 12, 9.99,  'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: ACDelco Gold Oil Filter PF63E'),
  (md5('inv_seed:MOTO-FL-500-S')::uuid,'MOTO-FL-500-S','01960004-0001-7000-8000-00000000001b'::uuid, 'GOODS_RECEIPT', 12, 12, 8.49,  'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Motorcraft FL-500-S Oil Filter'),
  (md5('inv_seed:WIXF-51394')::uuid,   'WIXF-51394',   '01960004-0001-7000-8000-00000000001c'::uuid, 'GOODS_RECEIPT', 12, 12, 8.99,  'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: WIX Standard Oil Filter 51394')
ON CONFLICT (ledger_entry_id) DO NOTHING;

-- Shelf C — Air/Cabin/Fuel Filters (Bins C-01..C-10)
INSERT INTO inventory_ledger_entry (ledger_entry_id, stock_item_id, location_id, event_type, change_in_quantity, quantity_after, unit_cost, unit_of_measure, "timestamp", created_at, updated_at, transaction_user_id, notes)
VALUES
  (md5('inv_seed:WIXF-42257')::uuid,   'WIXF-42257',  '01960004-0001-7000-8000-00000000001d'::uuid, 'GOODS_RECEIPT', 6,  6,  19.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: WIX Air Filter 42257'),
  (md5('inv_seed:WIXF-46038')::uuid,   'WIXF-46038',  '01960004-0001-7000-8000-00000000001e'::uuid, 'GOODS_RECEIPT', 6,  6,  18.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: WIX Cabin Air Filter 46038'),
  (md5('inv_seed:FRAM-CA8037')::uuid,  'FRAM-CA8037', '01960004-0001-7000-8000-00000000001f'::uuid, 'GOODS_RECEIPT', 6,  6,  12.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Fram Fresh Breeze Cabin Air Filter CA8037'),
  (md5('inv_seed:BSCH-5063WS')::uuid,  'BSCH-5063WS', '01960004-0001-7000-8000-000000000020'::uuid, 'GOODS_RECEIPT', 6,  6,  21.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Bosch HEPA Cabin Air Filter 5063WS'),
  (md5('inv_seed:ACDL-A2873C')::uuid,  'ACDL-A2873C', '01960004-0001-7000-8000-000000000021'::uuid, 'GOODS_RECEIPT', 6,  6,  14.49, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: ACDelco Gold Air Filter A2873C'),
  (md5('inv_seed:WIXF-49113')::uuid,   'WIXF-49113',  '01960004-0001-7000-8000-000000000022'::uuid, 'GOODS_RECEIPT', 6,  6,  16.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: WIX Cabin Air Filter 49113'),
  (md5('inv_seed:FRAM-CF10285')::uuid, 'FRAM-CF10285','01960004-0001-7000-8000-000000000023'::uuid, 'GOODS_RECEIPT', 6,  6,  11.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Fram Fresh Breeze Cabin Filter CF10285'),
  (md5('inv_seed:BSCH-CAFC6061')::uuid,'BSCH-CAFC6061','01960004-0001-7000-8000-000000000024'::uuid,'GOODS_RECEIPT', 6,  6,  19.49, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Bosch Cabin Air Filter CAFC6061'),
  (md5('inv_seed:WIXF-33481')::uuid,   'WIXF-33481',  '01960004-0001-7000-8000-000000000025'::uuid, 'GOODS_RECEIPT', 6,  6,  13.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: WIX Fuel Filter 33481'),
  (md5('inv_seed:BSCH-FF71047')::uuid, 'BSCH-FF71047','01960004-0001-7000-8000-000000000026'::uuid, 'GOODS_RECEIPT', 6,  6,  15.49, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Bosch Fuel Filter 71047')
ON CONFLICT (ledger_entry_id) DO NOTHING;

-- Shelf D — Spark Plugs & Windshield Wipers (Bins D-01..D-10)
INSERT INTO inventory_ledger_entry (ledger_entry_id, stock_item_id, location_id, event_type, change_in_quantity, quantity_after, unit_cost, unit_of_measure, "timestamp", created_at, updated_at, transaction_user_id, notes)
VALUES
  (md5('inv_seed:NGKP-TR55IX')::uuid,  'NGKP-TR55IX', '01960004-0001-7000-8000-000000000027'::uuid, 'GOODS_RECEIPT', 16, 16, 12.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: NGK Iridium IX Spark Plug TR55IX'),
  (md5('inv_seed:NGKP-BKR6EIX')::uuid,'NGKP-BKR6EIX','01960004-0001-7000-8000-000000000028'::uuid, 'GOODS_RECEIPT', 16, 16, 12.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: NGK Iridium IX Spark Plug BKR6EIX'),
  (md5('inv_seed:BSCH-SP9657')::uuid,  'BSCH-SP9657', '01960004-0001-7000-8000-000000000029'::uuid, 'GOODS_RECEIPT', 16, 16, 10.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Bosch Double Iridium Spark Plug 9657'),
  (md5('inv_seed:BSCH-SP4418')::uuid,  'BSCH-SP4418', '01960004-0001-7000-8000-00000000002a'::uuid, 'GOODS_RECEIPT', 16, 16, 10.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Bosch Double Iridium Spark Plug 4418'),
  (md5('inv_seed:DENS-ITV20TT')::uuid, 'DENS-ITV20TT','01960004-0001-7000-8000-00000000002b'::uuid, 'GOODS_RECEIPT', 16, 16, 11.49, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Denso Iridium TT Spark Plug ITV20TT'),
  (md5('inv_seed:ACDL-41-162')::uuid,  'ACDL-41-162', '01960004-0001-7000-8000-00000000002c'::uuid, 'GOODS_RECEIPT', 16, 16, 9.99,  'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: ACDelco Professional Iridium Spark Plug 41-162'),
  (md5('inv_seed:BSCH-26A')::uuid,     'BSCH-26A',    '01960004-0001-7000-8000-00000000002d'::uuid, 'GOODS_RECEIPT', 12, 12, 16.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Bosch ICON Wiper Blade 26"'),
  (md5('inv_seed:BSCH-24A')::uuid,     'BSCH-24A',    '01960004-0001-7000-8000-00000000002e'::uuid, 'GOODS_RECEIPT', 12, 12, 15.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Bosch ICON Wiper Blade 24"'),
  (md5('inv_seed:BSCH-22A')::uuid,     'BSCH-22A',    '01960004-0001-7000-8000-00000000002f'::uuid, 'GOODS_RECEIPT', 12, 12, 14.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Bosch ICON Wiper Blade 22"'),
  (md5('inv_seed:DENS-160-3314')::uuid,'DENS-160-3314','01960004-0001-7000-8000-000000000030'::uuid,'GOODS_RECEIPT', 12, 12, 17.49, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Denso Wiper Blade 160-3314')
ON CONFLICT (ledger_entry_id) DO NOTHING;

-- Shelf E — Brakes, Batteries & Misc (Bins E-01..E-10)
INSERT INTO inventory_ledger_entry (ledger_entry_id, stock_item_id, location_id, event_type, change_in_quantity, quantity_after, unit_cost, unit_of_measure, "timestamp", created_at, updated_at, transaction_user_id, notes)
VALUES
  (md5('inv_seed:WAGN-MX882')::uuid,   'WAGN-MX882',  '01960004-0001-7000-8000-000000000031'::uuid, 'GOODS_RECEIPT', 8,  8,  34.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Wagner ThermoQuiet Ceramic Brake Pad MX882'),
  (md5('inv_seed:WAGN-QC1394')::uuid,  'WAGN-QC1394', '01960004-0001-7000-8000-000000000032'::uuid, 'GOODS_RECEIPT', 8,  8,  29.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Wagner QuickStop Ceramic Brake Pad ZD1394'),
  (md5('inv_seed:BREM-P83073N')::uuid, 'BREM-P83073N','01960004-0001-7000-8000-000000000033'::uuid, 'GOODS_RECEIPT', 8,  8,  44.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Brembo NAO Ceramic Brake Pad P83073N'),
  (md5('inv_seed:BSCH-BC905')::uuid,   'BSCH-BC905',  '01960004-0001-7000-8000-000000000034'::uuid, 'GOODS_RECEIPT', 8,  8,  31.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Bosch BC905 QuietCast Premium Ceramic Brake Pad'),
  (md5('inv_seed:ACDL-48AGM')::uuid,   'ACDL-48AGM',  '01960004-0001-7000-8000-000000000035'::uuid, 'GOODS_RECEIPT', 4,  4,  189.99,'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: ACDelco Gold 48AGM AGM Battery'),
  (md5('inv_seed:INTS-MTP-65')::uuid,  'INTS-MTP-65', '01960004-0001-7000-8000-000000000036'::uuid, 'GOODS_RECEIPT', 4,  4,  149.99,'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Interstate MT-65 Battery'),
  (md5('inv_seed:PRES-AS262Y')::uuid,  'PRES-AS262Y', '01960004-0001-7000-8000-000000000037'::uuid, 'GOODS_RECEIPT', 6,  6,  10.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Prestone Wiper Fluid AS262Y'),
  (md5('inv_seed:PRES-AS657Y')::uuid,  'PRES-AS657Y', '01960004-0001-7000-8000-000000000038'::uuid, 'GOODS_RECEIPT', 6,  6,  6.99,  'GAL',NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Prestone Bug Wash Wiper Fluid AS657Y'),
  (md5('inv_seed:PRES-AS105')::uuid,   'PRES-AS105',  '01960004-0001-7000-8000-000000000039'::uuid, 'GOODS_RECEIPT', 6,  6,  5.99,  'GAL',NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Prestone All Season Washer Fluid AS105'),
  (md5('inv_seed:BREM-25988')::uuid,   'BREM-25988',  '01960004-0001-7000-8000-00000000003a'::uuid, 'GOODS_RECEIPT', 4,  4,  79.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Brembo Drilled Rotor 25988')
ON CONFLICT (ledger_entry_id) DO NOTHING;

-- Tire Rack A (...047) — Michelin CrossClimate2
INSERT INTO inventory_ledger_entry (ledger_entry_id, stock_item_id, location_id, event_type, change_in_quantity, quantity_after, unit_cost, unit_of_measure, "timestamp", created_at, updated_at, transaction_user_id, notes)
VALUES
  (md5('inv_seed:MICH-XC2-0001')::uuid, 'MICH-XC2-0001', '01960004-0001-7000-8000-000000000047'::uuid, 'GOODS_RECEIPT', 8, 8, 159.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Michelin CrossClimate2 205/55R16'),
  (md5('inv_seed:MICH-XC2-0002')::uuid, 'MICH-XC2-0002', '01960004-0001-7000-8000-000000000047'::uuid, 'GOODS_RECEIPT', 8, 8, 169.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Michelin CrossClimate2 215/55R17'),
  (md5('inv_seed:MICH-XC2-0003')::uuid, 'MICH-XC2-0003', '01960004-0001-7000-8000-000000000047'::uuid, 'GOODS_RECEIPT', 8, 8, 179.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Michelin CrossClimate2 225/45R18'),
  (md5('inv_seed:MICH-XC2-0006')::uuid, 'MICH-XC2-0006', '01960004-0001-7000-8000-000000000047'::uuid, 'GOODS_RECEIPT', 8, 8, 189.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Michelin CrossClimate2 265/70R17')
ON CONFLICT (ledger_entry_id) DO NOTHING;

-- Tire Rack B (...048) — Michelin Defender T+H & Pilot Sport 4S
INSERT INTO inventory_ledger_entry (ledger_entry_id, stock_item_id, location_id, event_type, change_in_quantity, quantity_after, unit_cost, unit_of_measure, "timestamp", created_at, updated_at, transaction_user_id, notes)
VALUES
  (md5('inv_seed:MICH-DEF-0006')::uuid, 'MICH-DEF-0006', '01960004-0001-7000-8000-000000000048'::uuid, 'GOODS_RECEIPT', 8, 8, 149.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Michelin Defender T+H 225/65R17'),
  (md5('inv_seed:MICH-DEF-0010')::uuid, 'MICH-DEF-0010', '01960004-0001-7000-8000-000000000048'::uuid, 'GOODS_RECEIPT', 8, 8, 159.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Michelin Defender T+H 235/60R18'),
  (md5('inv_seed:MICH-DEF-0011')::uuid, 'MICH-DEF-0011', '01960004-0001-7000-8000-000000000048'::uuid, 'GOODS_RECEIPT', 8, 8, 169.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Michelin Defender T+H 265/70R17'),
  (md5('inv_seed:MICH-PS4-0003')::uuid, 'MICH-PS4-0003', '01960004-0001-7000-8000-000000000048'::uuid, 'GOODS_RECEIPT', 4, 4, 229.99, 'EA', NOW(), NOW(), NOW(), 'system-seed', 'Initial stock: Michelin Pilot Sport 4S 245/40R18')
ON CONFLICT (ledger_entry_id) DO NOTHING;
