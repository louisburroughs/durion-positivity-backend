-- Repeatable seed migration for pos-shop-manager mechanic and mechanic_skill tables.
-- Sourced from TECHNICIAN-role employees in pos-people / pos-security-service.
-- 7 active technicians across CLT-MAIN-001, CLT-SOUTH-001, CLT-NORTH-001.
--
-- person_id values are the pos-people UUIDs stored as text (varchar(255)).
-- mechanic_id prefix: 01960040-0000-7000-8000-
-- mechanic_skill.id:  md5('mechanic_skill:'||mechanic_id||':'||skill_code)::uuid
-- Skill codes follow ASE T-series truck certification naming.
-- proficiency_level: 1 (beginner) – 5 (master).

SET TIME ZONE 'UTC';

-- ============================================================
-- mechanic rows
-- Idempotency: ON CONFLICT (person_id) DO UPDATE
-- ============================================================
INSERT INTO mechanic (
    mechanic_id, person_id, first_name, last_name,
    status, version, hire_date, last_synced_at, created_at, updated_at
)
VALUES
    -- kyle.brennan → CLT-MAIN-001 | Engine Specialist
    ('01960040-0000-7000-8000-000000000005'::uuid,
     '01960011-0000-7000-8000-000000000005',
     'Kyle', 'Brennan', 'ACTIVE', 1, CURRENT_DATE, NOW(), NOW(), NOW()),

    -- deshawn.morris → CLT-MAIN-001 | Drivetrain & Brakes
    ('01960040-0000-7000-8000-000000000006'::uuid,
     '01960011-0000-7000-8000-000000000006',
     'DeShawn', 'Morris', 'ACTIVE', 1, CURRENT_DATE, NOW(), NOW(), NOW()),

    -- carlos.ruiz → CLT-MAIN-001 | Electrical & HVAC
    ('01960040-0000-7000-8000-000000000007'::uuid,
     '01960011-0000-7000-8000-000000000007',
     'Carlos', 'Ruiz', 'ACTIVE', 1, CURRENT_DATE, NOW(), NOW(), NOW()),

    -- amber.nguyen → CLT-SOUTH-001 | Preventive Maintenance & Suspension
    ('01960040-0000-7000-8000-000000000008'::uuid,
     '01960011-0000-7000-8000-000000000008',
     'Amber', 'Nguyen', 'ACTIVE', 1, CURRENT_DATE, NOW(), NOW(), NOW()),

    -- eddie.vasquez → CLT-SOUTH-001 | Diesel & General
    ('01960040-0000-7000-8000-000000000009'::uuid,
     '01960011-0000-7000-8000-000000000009',
     'Eddie', 'Vasquez', 'ACTIVE', 1, CURRENT_DATE, NOW(), NOW(), NOW()),

    -- priya.patel → CLT-NORTH-001 | Brakes & Electrical
    ('01960040-0000-7000-8000-00000000000a'::uuid,
     '01960011-0000-7000-8000-00000000000a',
     'Priya', 'Patel', 'ACTIVE', 1, CURRENT_DATE, NOW(), NOW(), NOW()),

    -- james.okafor → CLT-NORTH-001 | Senior All-Around
    ('01960040-0000-7000-8000-00000000000b'::uuid,
     '01960011-0000-7000-8000-00000000000b',
     'James', 'Okafor', 'ACTIVE', 1, CURRENT_DATE, NOW(), NOW(), NOW())

ON CONFLICT (person_id) DO UPDATE
    SET first_name     = EXCLUDED.first_name,
        last_name      = EXCLUDED.last_name,
        status         = EXCLUDED.status,
        last_synced_at = NOW(),
        updated_at     = NOW();

-- ============================================================
-- mechanic_skill rows
-- Skill codes follow ASE T-series truck certification naming:
--   T1-GAS-ENGINE     – Gas Engines (heavy truck)
--   T2-DIESEL-ENGINE  – Diesel Engines (heavy truck)
--   T3-DRIVE-TRAIN    – Drive Train
--   T4-BRAKES         – Brakes
--   T5-STEERING       – Suspension & Steering
--   A4-SUSPENSION     – Suspension & Steering (light/medium)
--   A5-BRAKES         – Brakes (light/medium)
--   A6-ELECTRICAL     – Electrical/Electronic Systems
--   A8-ENGINE-PERF    – Engine Performance
--   T6-ELECTRICAL     – Electrical/Electronic Systems (heavy truck)
--   T7-HVAC           – Heating, Ventilation & Air Conditioning
--   T8-PMI            – Preventive Maintenance Inspection
-- Idempotency: deterministic id, ON CONFLICT (id) DO UPDATE
-- ============================================================
INSERT INTO mechanic_skill (
    id, mechanic_id, skill_code, proficiency_level, certified_date, created_at, updated_at
)
VALUES
    -- kyle.brennan: Engine Specialist
    (md5('mechanic_skill:01960040-0000-7000-8000-000000000005:T2-DIESEL-ENGINE')::uuid,
     '01960040-0000-7000-8000-000000000005'::uuid, 'T2-DIESEL-ENGINE', 5, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-000000000005:T1-GAS-ENGINE')::uuid,
     '01960040-0000-7000-8000-000000000005'::uuid, 'T1-GAS-ENGINE',    4, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-000000000005:A8-ENGINE-PERF')::uuid,
     '01960040-0000-7000-8000-000000000005'::uuid, 'A8-ENGINE-PERF',   4, CURRENT_DATE, NOW(), NOW()),

    -- deshawn.morris: Drivetrain & Brakes
    (md5('mechanic_skill:01960040-0000-7000-8000-000000000006:T3-DRIVE-TRAIN')::uuid,
     '01960040-0000-7000-8000-000000000006'::uuid, 'T3-DRIVE-TRAIN', 5, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-000000000006:T4-BRAKES')::uuid,
     '01960040-0000-7000-8000-000000000006'::uuid, 'T4-BRAKES',      4, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-000000000006:A5-BRAKES')::uuid,
     '01960040-0000-7000-8000-000000000006'::uuid, 'A5-BRAKES',      4, CURRENT_DATE, NOW(), NOW()),

    -- carlos.ruiz: Electrical & HVAC
    (md5('mechanic_skill:01960040-0000-7000-8000-000000000007:T6-ELECTRICAL')::uuid,
     '01960040-0000-7000-8000-000000000007'::uuid, 'T6-ELECTRICAL', 5, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-000000000007:A6-ELECTRICAL')::uuid,
     '01960040-0000-7000-8000-000000000007'::uuid, 'A6-ELECTRICAL', 4, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-000000000007:T7-HVAC')::uuid,
     '01960040-0000-7000-8000-000000000007'::uuid, 'T7-HVAC',       3, CURRENT_DATE, NOW(), NOW()),

    -- amber.nguyen: Preventive Maintenance & Suspension
    (md5('mechanic_skill:01960040-0000-7000-8000-000000000008:T8-PMI')::uuid,
     '01960040-0000-7000-8000-000000000008'::uuid, 'T8-PMI',        5, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-000000000008:A4-SUSPENSION')::uuid,
     '01960040-0000-7000-8000-000000000008'::uuid, 'A4-SUSPENSION', 4, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-000000000008:T5-STEERING')::uuid,
     '01960040-0000-7000-8000-000000000008'::uuid, 'T5-STEERING',   3, CURRENT_DATE, NOW(), NOW()),

    -- eddie.vasquez: Diesel & General
    (md5('mechanic_skill:01960040-0000-7000-8000-000000000009:T2-DIESEL-ENGINE')::uuid,
     '01960040-0000-7000-8000-000000000009'::uuid, 'T2-DIESEL-ENGINE', 4, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-000000000009:T3-DRIVE-TRAIN')::uuid,
     '01960040-0000-7000-8000-000000000009'::uuid, 'T3-DRIVE-TRAIN',   4, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-000000000009:T8-PMI')::uuid,
     '01960040-0000-7000-8000-000000000009'::uuid, 'T8-PMI',           3, CURRENT_DATE, NOW(), NOW()),

    -- priya.patel: Brakes & Electrical
    (md5('mechanic_skill:01960040-0000-7000-8000-00000000000a:T4-BRAKES')::uuid,
     '01960040-0000-7000-8000-00000000000a'::uuid, 'T4-BRAKES',     5, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-00000000000a:T6-ELECTRICAL')::uuid,
     '01960040-0000-7000-8000-00000000000a'::uuid, 'T6-ELECTRICAL', 4, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-00000000000a:A5-BRAKES')::uuid,
     '01960040-0000-7000-8000-00000000000a'::uuid, 'A5-BRAKES',     4, CURRENT_DATE, NOW(), NOW()),

    -- james.okafor: Senior All-Around
    (md5('mechanic_skill:01960040-0000-7000-8000-00000000000b:T1-GAS-ENGINE')::uuid,
     '01960040-0000-7000-8000-00000000000b'::uuid, 'T1-GAS-ENGINE',      5, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-00000000000b:T2-DIESEL-ENGINE')::uuid,
     '01960040-0000-7000-8000-00000000000b'::uuid, 'T2-DIESEL-ENGINE',   5, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-00000000000b:T3-DRIVE-TRAIN')::uuid,
     '01960040-0000-7000-8000-00000000000b'::uuid, 'T3-DRIVE-TRAIN',     4, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-00000000000b:T4-BRAKES')::uuid,
     '01960040-0000-7000-8000-00000000000b'::uuid, 'T4-BRAKES',          4, CURRENT_DATE, NOW(), NOW()),
    (md5('mechanic_skill:01960040-0000-7000-8000-00000000000b:T8-PMI')::uuid,
     '01960040-0000-7000-8000-00000000000b'::uuid, 'T8-PMI',             5, CURRENT_DATE, NOW(), NOW())

ON CONFLICT (id) DO UPDATE
    SET proficiency_level = EXCLUDED.proficiency_level,
        certified_date    = EXCLUDED.certified_date,
        updated_at        = NOW();
