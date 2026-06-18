-- Repeatable seed migration for location operational data.
-- Notes:
-- - Kept idempotent via ON CONFLICT and WHERE NOT EXISTS clauses.
-- - Locations inserted with NULL staging/quarantine FKs first to break circular reference.
-- - Storage locations inserted next, then location FKs back-filled via UPDATE.
-- - ON CONFLICT uses column names only (H2 compatibility — no constraint-name syntax).
SET TIME ZONE 'UTC';

-- =========================================================================
-- LOCATIONS (default_staging_location_id / default_quarantine_location_id
--            set to NULL initially to avoid circular FK during insert)
-- =========================================================================
INSERT INTO location (id, name, normalized_name, code, status, is_active, version,
                      timezone, address_line1, city, state, postal_code, country,
                      location_type_id, created_at, updated_at)
VALUES ('01960003-0000-7000-8000-000000000001'::uuid,
        'Charlotte Main Service Center',
        'charlotte main service center',
        'CLT-MAIN-001',
        'ACTIVE', TRUE, 0,
        'America/New_York', '100 Trade Street', 'Charlotte', 'NC', '28202', 'US',
        '01960001-0000-7000-8000-000000000001'::uuid,
        NOW(), NOW())
ON CONFLICT (code) DO NOTHING;

INSERT INTO location (id, name, normalized_name, code, status, is_active, version,
                      timezone, address_line1, city, state, postal_code, country,
                      location_type_id, created_at, updated_at)
VALUES ('01960003-0000-7000-8000-000000000002'::uuid,
        'South Charlotte Service Center',
        'south charlotte service center',
        'CLT-SOUTH-001',
        'ACTIVE', TRUE, 0,
        'America/New_York', '9801 Rea Road', 'Charlotte', 'NC', '28277', 'US',
        '01960001-0000-7000-8000-000000000001'::uuid,
        NOW(), NOW())
ON CONFLICT (code) DO NOTHING;

INSERT INTO location (id, name, normalized_name, code, status, is_active, version,
                      timezone, address_line1, city, state, postal_code, country,
                      location_type_id, created_at, updated_at)
VALUES ('01960003-0000-7000-8000-000000000003'::uuid,
        'North Charlotte Service Center',
        'north charlotte service center',
        'CLT-NORTH-001',
        'ACTIVE', TRUE, 0,
        'America/New_York', '1 Gilead Road', 'Huntersville', 'NC', '28078', 'US',
        '01960001-0000-7000-8000-000000000001'::uuid,
        NOW(), NOW())
ON CONFLICT (code) DO NOTHING;

INSERT INTO location (id, name, normalized_name, code, status, is_active, version,
                      timezone, city, state, postal_code, country,
                      location_type_id, created_at, updated_at)
VALUES ('01960003-0000-7000-8000-000000000004'::uuid,
        'Charlotte Mobile Operations Hub',
        'charlotte mobile operations hub',
        'CLT-MOB-HUB-001',
        'ACTIVE', TRUE, 0,
        'America/New_York', 'Charlotte', 'NC', '28206', 'US',
        '01960001-0000-7000-8000-000000000005'::uuid,
        NOW(), NOW())
ON CONFLICT (code) DO NOTHING;

INSERT INTO location (id, name, normalized_name, code, status, is_active, version,
                      timezone, address_line1, city, state, postal_code, country,
                      location_type_id, created_at, updated_at)
VALUES ('01960003-0000-7000-8000-000000000005'::uuid,
        'Durion Corporate Headquarters',
        'durion corporate headquarters',
        'CORP-HQ-001',
        'ACTIVE', TRUE, 0,
        'America/New_York', '200 South College Street', 'Charlotte', 'NC', '28202', 'US',
        '01960001-0000-7000-8000-000000000004'::uuid,
        NOW(), NOW())
ON CONFLICT (code) DO NOTHING;

-- =========================================================================
-- STORAGE LOCATIONS (site_id FK → locations inserted above)
-- =========================================================================
-- Charlotte Main
INSERT INTO storage_location (id, name, type, status, site_id, created_at, updated_at)
VALUES ('01960004-0001-7000-8000-000000000001'::uuid,
        'Main Staging Floor', 'FLOOR', 'ACTIVE',
        '01960003-0000-7000-8000-000000000001'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO storage_location (id, name, type, status, site_id, created_at, updated_at)
VALUES ('01960004-0001-7000-8000-000000000002'::uuid,
        'Main Quarantine Cage', 'CAGE', 'ACTIVE',
        '01960003-0000-7000-8000-000000000001'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO storage_location (id, name, type, status, site_id, created_at, updated_at)
VALUES ('01960004-0001-7000-8000-000000000003'::uuid,
        'Main Parts Shelf', 'SHELF', 'ACTIVE',
        '01960003-0000-7000-8000-000000000001'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- South Charlotte
INSERT INTO storage_location (id, name, type, status, site_id, created_at, updated_at)
VALUES ('01960004-0002-7000-8000-000000000001'::uuid,
        'South Staging Floor', 'FLOOR', 'ACTIVE',
        '01960003-0000-7000-8000-000000000002'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO storage_location (id, name, type, status, site_id, created_at, updated_at)
VALUES ('01960004-0002-7000-8000-000000000002'::uuid,
        'South Quarantine Cage', 'CAGE', 'ACTIVE',
        '01960003-0000-7000-8000-000000000002'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- North Charlotte
INSERT INTO storage_location (id, name, type, status, site_id, created_at, updated_at)
VALUES ('01960004-0003-7000-8000-000000000001'::uuid,
        'North Staging Floor', 'FLOOR', 'ACTIVE',
        '01960003-0000-7000-8000-000000000003'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO storage_location (id, name, type, status, site_id, created_at, updated_at)
VALUES ('01960004-0003-7000-8000-000000000002'::uuid,
        'North Quarantine Cage', 'CAGE', 'ACTIVE',
        '01960003-0000-7000-8000-000000000003'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- =========================================================================
-- RESOLVE CIRCULAR FK: back-fill staging + quarantine location FKs
-- Guarded by IS NULL so re-runs are idempotent without overwriting manual changes
-- =========================================================================
UPDATE location
SET    default_staging_location_id    = '01960004-0001-7000-8000-000000000001'::uuid,
       default_quarantine_location_id = '01960004-0001-7000-8000-000000000002'::uuid
WHERE  id = '01960003-0000-7000-8000-000000000001'::uuid
  AND  default_staging_location_id IS NULL;

UPDATE location
SET    default_staging_location_id    = '01960004-0002-7000-8000-000000000001'::uuid,
       default_quarantine_location_id = '01960004-0002-7000-8000-000000000002'::uuid
WHERE  id = '01960003-0000-7000-8000-000000000002'::uuid
  AND  default_staging_location_id IS NULL;

UPDATE location
SET    default_staging_location_id    = '01960004-0003-7000-8000-000000000001'::uuid,
       default_quarantine_location_id = '01960004-0003-7000-8000-000000000002'::uuid
WHERE  id = '01960003-0000-7000-8000-000000000003'::uuid
  AND  default_staging_location_id IS NULL;

-- =========================================================================
-- BAYS
-- normalized_name = lower(trim(name)) — matches BayEntity.java normalization
-- Timestamp column is last_modified_at (not updated_at) per BayEntity.java
-- =========================================================================
-- Charlotte Main — 4 bays
INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0001-7000-8000-000000000001'::uuid,
        '01960003-0000-7000-8000-000000000001'::uuid,
        'Bay 01', 'bay 01', 'GENERAL_SERVICE', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;

INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0001-7000-8000-000000000002'::uuid,
        '01960003-0000-7000-8000-000000000001'::uuid,
        'Bay 02', 'bay 02', 'ALIGNMENT', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;

INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0001-7000-8000-000000000003'::uuid,
        '01960003-0000-7000-8000-000000000001'::uuid,
        'Bay 03', 'bay 03', 'TIRE_SERVICE', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;

INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0001-7000-8000-000000000004'::uuid,
        '01960003-0000-7000-8000-000000000001'::uuid,
        'Bay 04', 'bay 04', 'INSPECTION', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;

-- South Charlotte — 3 bays
INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0002-7000-8000-000000000001'::uuid,
        '01960003-0000-7000-8000-000000000002'::uuid,
        'Bay 01', 'bay 01', 'GENERAL_SERVICE', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;

INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0002-7000-8000-000000000002'::uuid,
        '01960003-0000-7000-8000-000000000002'::uuid,
        'Bay 02', 'bay 02', 'ALIGNMENT', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;

INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0002-7000-8000-000000000003'::uuid,
        '01960003-0000-7000-8000-000000000002'::uuid,
        'Bay 03', 'bay 03', 'TIRE_SERVICE', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;

-- North Charlotte — 3 bays
INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0003-7000-8000-000000000001'::uuid,
        '01960003-0000-7000-8000-000000000003'::uuid,
        'Bay 01', 'bay 01', 'GENERAL_SERVICE', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;

INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0003-7000-8000-000000000002'::uuid,
        '01960003-0000-7000-8000-000000000003'::uuid,
        'Bay 02', 'bay 02', 'TIRE_SERVICE', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;

INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0003-7000-8000-000000000003'::uuid,
        '01960003-0000-7000-8000-000000000003'::uuid,
        'Bay 03', 'bay 03', 'INSPECTION', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;

-- =========================================================================
-- MOBILE UNITS (base: Charlotte Mobile Operations Hub)
-- =========================================================================
INSERT INTO mobile_units (id, name, status, base_location_id, travel_buffer_policy_id, created_at, updated_at)
VALUES ('01960006-0000-7000-8000-000000000001'::uuid,
        'MU-Charlotte-01', 'ACTIVE',
        '01960003-0000-7000-8000-000000000004'::uuid,
        '407617dd-f4f6-fec6-50e4-4221bdf102c8'::uuid,
        NOW(), NOW())
ON CONFLICT (base_location_id, name) DO NOTHING;

INSERT INTO mobile_units (id, name, status, base_location_id, travel_buffer_policy_id, created_at, updated_at)
VALUES ('01960006-0000-7000-8000-000000000002'::uuid,
        'MU-Charlotte-02', 'ACTIVE',
        '01960003-0000-7000-8000-000000000004'::uuid,
        '01960002-0000-7000-8000-000000000001'::uuid,
        NOW(), NOW())
ON CONFLICT (base_location_id, name) DO NOTHING;

-- =========================================================================
-- MOBILE UNIT CAPABILITIES
-- Table has no PK or unique constraint — use WHERE NOT EXISTS for idempotency
-- =========================================================================
-- MU-Charlotte-01: OIL_CHANGE, PM_SERVICE, ENGINE_DIAGNOSTICS, ROADSIDE_SERVICE
INSERT INTO mobile_unit_capabilities (mobile_unit_id, service_capability_id)
SELECT '01960006-0000-7000-8000-000000000001'::uuid, 'a8892380-c066-9e4d-6982-5e918c78514d'::uuid
WHERE NOT EXISTS (
    SELECT 1 FROM mobile_unit_capabilities
    WHERE mobile_unit_id = '01960006-0000-7000-8000-000000000001'::uuid
      AND service_capability_id = 'a8892380-c066-9e4d-6982-5e918c78514d'::uuid);

INSERT INTO mobile_unit_capabilities (mobile_unit_id, service_capability_id)
SELECT '01960006-0000-7000-8000-000000000001'::uuid, '92890323-55d9-c743-1b8c-97e97fe3a5ee'::uuid
WHERE NOT EXISTS (
    SELECT 1 FROM mobile_unit_capabilities
    WHERE mobile_unit_id = '01960006-0000-7000-8000-000000000001'::uuid
      AND service_capability_id = '92890323-55d9-c743-1b8c-97e97fe3a5ee'::uuid);

INSERT INTO mobile_unit_capabilities (mobile_unit_id, service_capability_id)
SELECT '01960006-0000-7000-8000-000000000001'::uuid, '98eba49d-cadd-5a65-3d13-23f7a5680ba1'::uuid
WHERE NOT EXISTS (
    SELECT 1 FROM mobile_unit_capabilities
    WHERE mobile_unit_id = '01960006-0000-7000-8000-000000000001'::uuid
      AND service_capability_id = '98eba49d-cadd-5a65-3d13-23f7a5680ba1'::uuid);

INSERT INTO mobile_unit_capabilities (mobile_unit_id, service_capability_id)
SELECT '01960006-0000-7000-8000-000000000001'::uuid, '498845ee-8f3a-e131-3bba-e1f1a0b53091'::uuid
WHERE NOT EXISTS (
    SELECT 1 FROM mobile_unit_capabilities
    WHERE mobile_unit_id = '01960006-0000-7000-8000-000000000001'::uuid
      AND service_capability_id = '498845ee-8f3a-e131-3bba-e1f1a0b53091'::uuid);

-- MU-Charlotte-02: OIL_CHANGE, PM_SERVICE, BRAKE_SERVICE, TIRE_SERVICE
INSERT INTO mobile_unit_capabilities (mobile_unit_id, service_capability_id)
SELECT '01960006-0000-7000-8000-000000000002'::uuid, 'a8892380-c066-9e4d-6982-5e918c78514d'::uuid
WHERE NOT EXISTS (
    SELECT 1 FROM mobile_unit_capabilities
    WHERE mobile_unit_id = '01960006-0000-7000-8000-000000000002'::uuid
      AND service_capability_id = 'a8892380-c066-9e4d-6982-5e918c78514d'::uuid);

INSERT INTO mobile_unit_capabilities (mobile_unit_id, service_capability_id)
SELECT '01960006-0000-7000-8000-000000000002'::uuid, '92890323-55d9-c743-1b8c-97e97fe3a5ee'::uuid
WHERE NOT EXISTS (
    SELECT 1 FROM mobile_unit_capabilities
    WHERE mobile_unit_id = '01960006-0000-7000-8000-000000000002'::uuid
      AND service_capability_id = '92890323-55d9-c743-1b8c-97e97fe3a5ee'::uuid);

INSERT INTO mobile_unit_capabilities (mobile_unit_id, service_capability_id)
SELECT '01960006-0000-7000-8000-000000000002'::uuid, '6cff0034-9829-8b80-390b-8e4e450af6a7'::uuid
WHERE NOT EXISTS (
    SELECT 1 FROM mobile_unit_capabilities
    WHERE mobile_unit_id = '01960006-0000-7000-8000-000000000002'::uuid
      AND service_capability_id = '6cff0034-9829-8b80-390b-8e4e450af6a7'::uuid);

INSERT INTO mobile_unit_capabilities (mobile_unit_id, service_capability_id)
SELECT '01960006-0000-7000-8000-000000000002'::uuid, '4060148a-347e-d792-efe9-9039d14e6eb8'::uuid
WHERE NOT EXISTS (
    SELECT 1 FROM mobile_unit_capabilities
    WHERE mobile_unit_id = '01960006-0000-7000-8000-000000000002'::uuid
      AND service_capability_id = '4060148a-347e-d792-efe9-9039d14e6eb8'::uuid);

-- =========================================================================
-- MOBILE UNIT COVERAGE RULES
-- =========================================================================
-- MU-Charlotte-01: Mecklenburg County (priority 1), South Meck/Ballantyne (priority 2)
INSERT INTO mobile_unit_coverage_rules (id, mobile_unit_id, service_area_id, rule_type, priority, created_at, updated_at)
VALUES ('01960007-0001-7000-8000-000000000001'::uuid,
        '01960006-0000-7000-8000-000000000001'::uuid,
        '42fdb342-ffba-9670-584b-6030b450a178'::uuid,
        'INCLUDE', 1, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO mobile_unit_coverage_rules (id, mobile_unit_id, service_area_id, rule_type, priority, created_at, updated_at)
VALUES ('01960007-0001-7000-8000-000000000002'::uuid,
        '01960006-0000-7000-8000-000000000001'::uuid,
        '633291b1-5487-1bfe-d183-182be094d44f'::uuid,
        'INCLUDE', 2, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- MU-Charlotte-02: Huntersville/Cornelius (priority 1)
INSERT INTO mobile_unit_coverage_rules (id, mobile_unit_id, service_area_id, rule_type, priority, created_at, updated_at)
VALUES ('01960007-0002-7000-8000-000000000001'::uuid,
        '01960006-0000-7000-8000-000000000002'::uuid,
        'df412fda-4a6a-f06d-4537-7b6dc351be47'::uuid,
        'INCLUDE', 1, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- =========================================================================
-- LOCATION PARENTS (hierarchy)
-- =========================================================================
-- CLT-MAIN-001 → CORP-HQ-001 (HEADQUARTERS)
INSERT INTO location_parent (id, child_id, parent_id, parent_type, created_at, updated_at)
VALUES ('01960008-0000-7000-8000-000000000001'::uuid,
        '01960003-0000-7000-8000-000000000001'::uuid,
        '01960003-0000-7000-8000-000000000005'::uuid,
        'HEADQUARTERS', NOW(), NOW())
ON CONFLICT (child_id, parent_type) DO NOTHING;

-- CLT-SOUTH-001 → CLT-MAIN-001 (DISTRICT)
INSERT INTO location_parent (id, child_id, parent_id, parent_type, created_at, updated_at)
VALUES ('01960008-0000-7000-8000-000000000002'::uuid,
        '01960003-0000-7000-8000-000000000002'::uuid,
        '01960003-0000-7000-8000-000000000001'::uuid,
        'DISTRICT', NOW(), NOW())
ON CONFLICT (child_id, parent_type) DO NOTHING;

-- CLT-NORTH-001 → CLT-MAIN-001 (DISTRICT)
INSERT INTO location_parent (id, child_id, parent_id, parent_type, created_at, updated_at)
VALUES ('01960008-0000-7000-8000-000000000003'::uuid,
        '01960003-0000-7000-8000-000000000003'::uuid,
        '01960003-0000-7000-8000-000000000001'::uuid,
        'DISTRICT', NOW(), NOW())
ON CONFLICT (child_id, parent_type) DO NOTHING;

-- CLT-MOB-HUB-001 → CLT-MAIN-001 (ORGANIZATIONAL)
INSERT INTO location_parent (id, child_id, parent_id, parent_type, created_at, updated_at)
VALUES ('01960008-0000-7000-8000-000000000004'::uuid,
        '01960003-0000-7000-8000-000000000004'::uuid,
        '01960003-0000-7000-8000-000000000001'::uuid,
        'ORGANIZATIONAL', NOW(), NOW())
ON CONFLICT (child_id, parent_type) DO NOTHING;

-- =============================================================================
-- STORAGE LOCATIONS — CLT-MAIN-001 shop
-- site_id: 01960003-0000-7000-8000-000000000001
-- parent prefix: 01960004-0001-7000-8000-
-- Existing:
--   ...000000000001  Main Staging Floor (FLOOR)
--   ...000000000002  Main Quarantine Cage (CAGE)
--   ...000000000003  Main Parts Shelf (SHELF)
-- New: 5 shelf sections (A-E), 50 bins, 2 tire racks
-- =============================================================================

-- Shelf sections A–E (parent = Main Parts Shelf ...003)
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0001-7000-8000-000000000004'::uuid, 'Shelf Section A', 'SHELF', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000003'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000005'::uuid, 'Shelf Section B', 'SHELF', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000003'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000006'::uuid, 'Shelf Section C', 'SHELF', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000003'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000007'::uuid, 'Shelf Section D', 'SHELF', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000003'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000008'::uuid, 'Shelf Section E', 'SHELF', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000003'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Bins A-01 through A-10 (parent = Shelf Section A ...004)
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0001-7000-8000-000000000009'::uuid, 'Bin A-01', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000004'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000000a'::uuid, 'Bin A-02', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000004'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000000b'::uuid, 'Bin A-03', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000004'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000000c'::uuid, 'Bin A-04', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000004'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000000d'::uuid, 'Bin A-05', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000004'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000000e'::uuid, 'Bin A-06', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000004'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000000f'::uuid, 'Bin A-07', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000004'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000010'::uuid, 'Bin A-08', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000004'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000011'::uuid, 'Bin A-09', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000004'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000012'::uuid, 'Bin A-10', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000004'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Bins B-01 through B-10 (parent = Shelf Section B ...005)
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0001-7000-8000-000000000013'::uuid, 'Bin B-01', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000005'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000014'::uuid, 'Bin B-02', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000005'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000015'::uuid, 'Bin B-03', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000005'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000016'::uuid, 'Bin B-04', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000005'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000017'::uuid, 'Bin B-05', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000005'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000018'::uuid, 'Bin B-06', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000005'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000019'::uuid, 'Bin B-07', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000005'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000001a'::uuid, 'Bin B-08', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000005'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000001b'::uuid, 'Bin B-09', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000005'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000001c'::uuid, 'Bin B-10', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000005'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Bins C-01 through C-10 (parent = Shelf Section C ...006)
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0001-7000-8000-00000000001d'::uuid, 'Bin C-01', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000006'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000001e'::uuid, 'Bin C-02', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000006'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000001f'::uuid, 'Bin C-03', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000006'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000020'::uuid, 'Bin C-04', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000006'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000021'::uuid, 'Bin C-05', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000006'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000022'::uuid, 'Bin C-06', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000006'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000023'::uuid, 'Bin C-07', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000006'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000024'::uuid, 'Bin C-08', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000006'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000025'::uuid, 'Bin C-09', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000006'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000026'::uuid, 'Bin C-10', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000006'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Bins D-01 through D-10 (parent = Shelf Section D ...007)
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0001-7000-8000-000000000027'::uuid, 'Bin D-01', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000007'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000028'::uuid, 'Bin D-02', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000007'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000029'::uuid, 'Bin D-03', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000007'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000002a'::uuid, 'Bin D-04', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000007'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000002b'::uuid, 'Bin D-05', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000007'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000002c'::uuid, 'Bin D-06', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000007'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000002d'::uuid, 'Bin D-07', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000007'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000002e'::uuid, 'Bin D-08', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000007'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000002f'::uuid, 'Bin D-09', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000007'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000030'::uuid, 'Bin D-10', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000007'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Bins E-01 through E-10 (parent = Shelf Section E ...008)
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0001-7000-8000-000000000031'::uuid, 'Bin E-01', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000032'::uuid, 'Bin E-02', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000033'::uuid, 'Bin E-03', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000034'::uuid, 'Bin E-04', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000035'::uuid, 'Bin E-05', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000036'::uuid, 'Bin E-06', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000037'::uuid, 'Bin E-07', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000038'::uuid, 'Bin E-08', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000039'::uuid, 'Bin E-09', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-00000000003a'::uuid, 'Bin E-10', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000008'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Tire Racks A & B (FLOOR type, parent = Main Staging Floor ...001)
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0001-7000-8000-000000000047'::uuid, 'Tire Rack A', 'FLOOR', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000001'::uuid, NOW(), NOW()),
  ('01960004-0001-7000-8000-000000000048'::uuid, 'Tire Rack B', 'FLOOR', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960004-0001-7000-8000-000000000001'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- =========================================================================
-- OPERATIONAL FILL: realistic bays, mobile units, and parts storage
-- for the three Charlotte service centers (CLT-MAIN / SOUTH / NORTH).
-- Idempotent via ON CONFLICT. Appended fill — does not alter rows above.
-- =========================================================================

-- ---- BAYS (top up each service center into the 6–10 range) ----
-- CLT-MAIN-001: +4 bays
INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0001-7000-8000-000000000005'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'Bay 05', 'bay 05', 'GENERAL_SERVICE', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;
INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0001-7000-8000-000000000006'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'Bay 06', 'bay 06', 'HEAVY_DUTY', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;
INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0001-7000-8000-000000000007'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'Bay 07', 'bay 07', 'TIRE_SERVICE', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;
INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0001-7000-8000-000000000008'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'Bay 08', 'bay 08', 'WASH_DETAIL', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;
-- CLT-SOUTH-001: +4 bays
INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0002-7000-8000-000000000004'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'Bay 04', 'bay 04', 'INSPECTION', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;
INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0002-7000-8000-000000000005'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'Bay 05', 'bay 05', 'GENERAL_SERVICE', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;
INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0002-7000-8000-000000000006'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'Bay 06', 'bay 06', 'HEAVY_DUTY', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;
INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0002-7000-8000-000000000007'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'Bay 07', 'bay 07', 'WASH_DETAIL', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;
-- CLT-NORTH-001: +3 bays
INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0003-7000-8000-000000000004'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'Bay 04', 'bay 04', 'ALIGNMENT', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;
INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0003-7000-8000-000000000005'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'Bay 05', 'bay 05', 'GENERAL_SERVICE', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;
INSERT INTO bays (id, location_id, name, normalized_name, bay_type, status, max_concurrent_vehicles, created_at, last_modified_at)
VALUES ('01960005-0003-7000-8000-000000000006'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'Bay 06', 'bay 06', 'HEAVY_DUTY', 'ACTIVE', 1, NOW(), NOW())
ON CONFLICT (location_id, normalized_name) DO NOTHING;

-- ---- MOBILE UNITS (based at each service center) ----
-- CLT-MAIN-001: 3 mobile units
INSERT INTO mobile_units (id, name, status, base_location_id, travel_buffer_policy_id, created_at, updated_at)
VALUES ('01960006-0001-7000-8000-000000000001'::uuid, 'MU-CLT-MAIN-01', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960002-0000-7000-8000-000000000001'::uuid, NOW(), NOW())
ON CONFLICT (base_location_id, name) DO NOTHING;
INSERT INTO mobile_units (id, name, status, base_location_id, travel_buffer_policy_id, created_at, updated_at)
VALUES ('01960006-0001-7000-8000-000000000002'::uuid, 'MU-CLT-MAIN-02', 'ACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960002-0000-7000-8000-000000000001'::uuid, NOW(), NOW())
ON CONFLICT (base_location_id, name) DO NOTHING;
INSERT INTO mobile_units (id, name, status, base_location_id, travel_buffer_policy_id, created_at, updated_at)
VALUES ('01960006-0001-7000-8000-000000000003'::uuid, 'MU-CLT-MAIN-03', 'INACTIVE', '01960003-0000-7000-8000-000000000001'::uuid, '01960002-0000-7000-8000-000000000001'::uuid, NOW(), NOW())
ON CONFLICT (base_location_id, name) DO NOTHING;
-- CLT-SOUTH-001: 2 mobile units
INSERT INTO mobile_units (id, name, status, base_location_id, travel_buffer_policy_id, created_at, updated_at)
VALUES ('01960006-0002-7000-8000-000000000001'::uuid, 'MU-CLT-SOUTH-01', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960002-0000-7000-8000-000000000001'::uuid, NOW(), NOW())
ON CONFLICT (base_location_id, name) DO NOTHING;
INSERT INTO mobile_units (id, name, status, base_location_id, travel_buffer_policy_id, created_at, updated_at)
VALUES ('01960006-0002-7000-8000-000000000002'::uuid, 'MU-CLT-SOUTH-02', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960002-0000-7000-8000-000000000001'::uuid, NOW(), NOW())
ON CONFLICT (base_location_id, name) DO NOTHING;
-- CLT-NORTH-001: 2 mobile units
INSERT INTO mobile_units (id, name, status, base_location_id, travel_buffer_policy_id, created_at, updated_at)
VALUES ('01960006-0003-7000-8000-000000000001'::uuid, 'MU-CLT-NORTH-01', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960002-0000-7000-8000-000000000001'::uuid, NOW(), NOW())
ON CONFLICT (base_location_id, name) DO NOTHING;
INSERT INTO mobile_units (id, name, status, base_location_id, travel_buffer_policy_id, created_at, updated_at)
VALUES ('01960006-0003-7000-8000-000000000002'::uuid, 'MU-CLT-NORTH-02', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960002-0000-7000-8000-000000000001'::uuid, NOW(), NOW())
ON CONFLICT (base_location_id, name) DO NOTHING;

-- ---- STORAGE: CLT-SOUTH-001 (South) — parts size mix + tire storage ----
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0002-7000-8000-000000000003'::uuid, 'South Parts Shelf', 'SHELF', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, NULL, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000004'::uuid, 'South Bulk Floor', 'FLOOR', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, NULL, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000005'::uuid, 'South Secured Parts Cage', 'CAGE', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, NULL, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000006'::uuid, 'South Tire Rack A', 'FLOOR', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, NULL, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000007'::uuid, 'South Tire Rack B', 'FLOOR', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, NULL, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0002-7000-8000-000000000008'::uuid, 'Shelf Section A', 'SHELF', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000003'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000009'::uuid, 'Shelf Section B', 'SHELF', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000003'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-00000000000a'::uuid, 'Shelf Section C', 'SHELF', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000003'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
-- South bins A-01..A-08
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0002-7000-8000-00000000000b'::uuid, 'Bin A-01', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-00000000000c'::uuid, 'Bin A-02', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-00000000000d'::uuid, 'Bin A-03', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-00000000000e'::uuid, 'Bin A-04', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-00000000000f'::uuid, 'Bin A-05', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000010'::uuid, 'Bin A-06', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000011'::uuid, 'Bin A-07', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000012'::uuid, 'Bin A-08', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000008'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
-- South bins B-01..B-08
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0002-7000-8000-000000000013'::uuid, 'Bin B-01', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000009'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000014'::uuid, 'Bin B-02', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000009'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000015'::uuid, 'Bin B-03', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000009'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000016'::uuid, 'Bin B-04', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000009'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000017'::uuid, 'Bin B-05', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000009'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000018'::uuid, 'Bin B-06', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000009'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000019'::uuid, 'Bin B-07', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000009'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-00000000001a'::uuid, 'Bin B-08', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-000000000009'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
-- South bins C-01..C-08
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0002-7000-8000-00000000001b'::uuid, 'Bin C-01', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-00000000000a'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-00000000001c'::uuid, 'Bin C-02', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-00000000000a'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-00000000001d'::uuid, 'Bin C-03', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-00000000000a'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-00000000001e'::uuid, 'Bin C-04', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-00000000000a'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-00000000001f'::uuid, 'Bin C-05', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-00000000000a'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000020'::uuid, 'Bin C-06', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-00000000000a'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000021'::uuid, 'Bin C-07', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-00000000000a'::uuid, NOW(), NOW()),
  ('01960004-0002-7000-8000-000000000022'::uuid, 'Bin C-08', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000002'::uuid, '01960004-0002-7000-8000-00000000000a'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ---- STORAGE: CLT-NORTH-001 (North) — parts size mix + tire storage ----
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0003-7000-8000-000000000003'::uuid, 'North Parts Shelf', 'SHELF', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, NULL, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000004'::uuid, 'North Bulk Floor', 'FLOOR', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, NULL, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000005'::uuid, 'North Secured Parts Cage', 'CAGE', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, NULL, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000006'::uuid, 'North Tire Rack A', 'FLOOR', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, NULL, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000007'::uuid, 'North Tire Rack B', 'FLOOR', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, NULL, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0003-7000-8000-000000000008'::uuid, 'Shelf Section A', 'SHELF', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000003'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000009'::uuid, 'Shelf Section B', 'SHELF', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000003'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-00000000000a'::uuid, 'Shelf Section C', 'SHELF', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000003'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
-- North bins A-01..A-08
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0003-7000-8000-00000000000b'::uuid, 'Bin A-01', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-00000000000c'::uuid, 'Bin A-02', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-00000000000d'::uuid, 'Bin A-03', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-00000000000e'::uuid, 'Bin A-04', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-00000000000f'::uuid, 'Bin A-05', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000010'::uuid, 'Bin A-06', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000011'::uuid, 'Bin A-07', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000008'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000012'::uuid, 'Bin A-08', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000008'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
-- North bins B-01..B-08
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0003-7000-8000-000000000013'::uuid, 'Bin B-01', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000009'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000014'::uuid, 'Bin B-02', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000009'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000015'::uuid, 'Bin B-03', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000009'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000016'::uuid, 'Bin B-04', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000009'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000017'::uuid, 'Bin B-05', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000009'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000018'::uuid, 'Bin B-06', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000009'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000019'::uuid, 'Bin B-07', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000009'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-00000000001a'::uuid, 'Bin B-08', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-000000000009'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
-- North bins C-01..C-08
INSERT INTO storage_location (id, name, type, status, site_id, parent_storage_location_id, created_at, updated_at)
VALUES
  ('01960004-0003-7000-8000-00000000001b'::uuid, 'Bin C-01', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-00000000000a'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-00000000001c'::uuid, 'Bin C-02', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-00000000000a'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-00000000001d'::uuid, 'Bin C-03', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-00000000000a'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-00000000001e'::uuid, 'Bin C-04', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-00000000000a'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-00000000001f'::uuid, 'Bin C-05', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-00000000000a'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000020'::uuid, 'Bin C-06', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-00000000000a'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000021'::uuid, 'Bin C-07', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-00000000000a'::uuid, NOW(), NOW()),
  ('01960004-0003-7000-8000-000000000022'::uuid, 'Bin C-08', 'BIN', 'ACTIVE', '01960003-0000-7000-8000-000000000003'::uuid, '01960004-0003-7000-8000-00000000000a'::uuid, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

