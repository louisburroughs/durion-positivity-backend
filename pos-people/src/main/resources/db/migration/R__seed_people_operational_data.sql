-- Repeatable seed migration for pos-people operational data.
-- Re-run marker 2026-06-24: bump checksum so Flyway re-applies this repeatable.
-- person_location_assignment rows are guarded by `WHERE EXISTS (location id=...)`; on the
-- first run the location rows did not yet exist (cross-module seed ordering), so every
-- assignment was skipped and the repeatable did not retry. Re-applying now that locations
-- are present populates them (idempotent via ON CONFLICT DO NOTHING).
-- 39 employees across 7 roles for Durion Positivity (medium truck mechanical repair corporation).
-- Service centers carry the full shop-operational role set and are staffed with
-- at least one technician per bay (±2); corporate roles remain at CORP-HQ-001.
-- Locations: CLT-MAIN-001, CLT-SOUTH-001, CLT-NORTH-001, CLT-MOB-HUB-001, CORP-HQ-001
--
-- Additional person rows (non-employees) for cross-service FK alignment:
--   01960024-*: 50 customer persons (person_party.person_id in pos-customer)
--   01960025-*: 20 commercial primary contacts (contact.person_id in pos-customer)
--   01960026-*: 20 commercial billing contacts (contact.person_id in pos-customer)
SET TIME ZONE 'UTC';

-- person rows
INSERT INTO person (id, first_name, last_name, employee_number, status, hire_date, created_at, updated_at)
VALUES
    ('01960011-0000-7000-8000-000000000001'::uuid, 'Marcus', 'Webb', 'EMP-0001', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000002'::uuid, 'Diana', 'Rowe', 'EMP-0002', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000003'::uuid, 'Terrence', 'Blake', 'EMP-0003', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000004'::uuid, 'Sandra', 'Cruz', 'EMP-0004', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000005'::uuid, 'Kyle', 'Brennan', 'EMP-0005', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000006'::uuid, 'DeShawn', 'Morris', 'EMP-0006', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000007'::uuid, 'Carlos', 'Ruiz', 'EMP-0007', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000008'::uuid, 'Amber', 'Nguyen', 'EMP-0008', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000009'::uuid, 'Eddie', 'Vasquez', 'EMP-0009', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000a'::uuid, 'Priya', 'Patel', 'EMP-0010', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000b'::uuid, 'James', 'Okafor', 'EMP-0011', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000c'::uuid, 'Rachel', 'Kim', 'EMP-0012', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000d'::uuid, 'Tyrone', 'Williams', 'EMP-0013', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000e'::uuid, 'Olivia', 'Chen', 'EMP-0014', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000f'::uuid, 'Harold', 'Sanders', 'EMP-0015', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000010'::uuid, 'Irene', 'Torres', 'EMP-0016', 'ACTIVE', CURRENT_DATE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Re-seed emails as EMAIL contact points (person email columns removed).
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000001'::uuid, 'EMAIL', 'marcus.webb@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000001'::uuid AND contact_type='EMAIL' AND value='marcus.webb@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000002'::uuid, 'EMAIL', 'diana.rowe@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000002'::uuid AND contact_type='EMAIL' AND value='diana.rowe@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000003'::uuid, 'EMAIL', 'terrence.blake@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000003'::uuid AND contact_type='EMAIL' AND value='terrence.blake@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000004'::uuid, 'EMAIL', 'sandra.cruz@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000004'::uuid AND contact_type='EMAIL' AND value='sandra.cruz@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000005'::uuid, 'EMAIL', 'kyle.brennan@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000005'::uuid AND contact_type='EMAIL' AND value='kyle.brennan@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000006'::uuid, 'EMAIL', 'deshawn.morris@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000006'::uuid AND contact_type='EMAIL' AND value='deshawn.morris@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000007'::uuid, 'EMAIL', 'carlos.ruiz@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000007'::uuid AND contact_type='EMAIL' AND value='carlos.ruiz@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000008'::uuid, 'EMAIL', 'amber.nguyen@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000008'::uuid AND contact_type='EMAIL' AND value='amber.nguyen@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000009'::uuid, 'EMAIL', 'eddie.vasquez@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000009'::uuid AND contact_type='EMAIL' AND value='eddie.vasquez@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-00000000000a'::uuid, 'EMAIL', 'priya.patel@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-00000000000a'::uuid AND contact_type='EMAIL' AND value='priya.patel@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-00000000000b'::uuid, 'EMAIL', 'james.okafor@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-00000000000b'::uuid AND contact_type='EMAIL' AND value='james.okafor@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-00000000000c'::uuid, 'EMAIL', 'rachel.kim@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-00000000000c'::uuid AND contact_type='EMAIL' AND value='rachel.kim@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-00000000000d'::uuid, 'EMAIL', 'tyrone.williams@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-00000000000d'::uuid AND contact_type='EMAIL' AND value='tyrone.williams@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-00000000000e'::uuid, 'EMAIL', 'olivia.chen@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-00000000000e'::uuid AND contact_type='EMAIL' AND value='olivia.chen@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-00000000000f'::uuid, 'EMAIL', 'harold.sanders@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-00000000000f'::uuid AND contact_type='EMAIL' AND value='harold.sanders@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000010'::uuid, 'EMAIL', 'irene.torres@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000010'::uuid AND contact_type='EMAIL' AND value='irene.torres@durion.internal');

-- =========================================================================
-- Group A: 50 customer persons (01960024-*) — person_party.person_id in pos-customer
-- =========================================================================

INSERT INTO person (id, first_name, last_name, status, created_at, updated_at)
VALUES
    ('01960024-0000-7000-8000-000000000001'::uuid, 'Marcus', 'Patterson', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000002'::uuid, 'Jennifer', 'Holloway', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000003'::uuid, 'Robert', 'Castillo', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000004'::uuid, 'Angela', 'Freeman', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000005'::uuid, 'Derek', 'Washington', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000006'::uuid, 'Patricia', 'Simmons', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000007'::uuid, 'Kevin', 'Thornton', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000008'::uuid, 'Linda', 'Guerrero', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000009'::uuid, 'James', 'Caldwell', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000a'::uuid, 'Tanya', 'Robinson', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000b'::uuid, 'Michael', 'Owens', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000c'::uuid, 'Cheryl', 'Hawkins', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000d'::uuid, 'Ronald', 'Jenkins', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000e'::uuid, 'Denise', 'Foster', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000f'::uuid, 'Anthony', 'Bryant', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000010'::uuid, 'Brenda', 'Coleman', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000011'::uuid, 'Steven', 'Gardner', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000012'::uuid, 'Nicole', 'Harrison', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000013'::uuid, 'Gary', 'Alexander', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000014'::uuid, 'Carolyn', 'Mitchell', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000015'::uuid, 'Timothy', 'Dixon', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000016'::uuid, 'Sandra', 'Reeves', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000017'::uuid, 'Walter', 'Hughes', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000018'::uuid, 'Pamela', 'Lewis', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000019'::uuid, 'Larry', 'Peterson', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001a'::uuid, 'Deborah', 'Barnes', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001b'::uuid, 'Frank', 'Murphy', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001c'::uuid, 'Sharon', 'Powell', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001d'::uuid, 'Raymond', 'Bailey', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001e'::uuid, 'Cynthia', 'Ross', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001f'::uuid, 'Jose', 'Rivera', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000020'::uuid, 'Gloria', 'Turner', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000021'::uuid, 'Douglas', 'Stewart', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000022'::uuid, 'Shirley', 'Flores', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000023'::uuid, 'Henry', 'Griffin', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000024'::uuid, 'Marie', 'Evans', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000025'::uuid, 'Bruce', 'King', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000026'::uuid, 'Wanda', 'Sanchez', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000027'::uuid, 'Keith', 'Ward', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000028'::uuid, 'Phyllis', 'Long', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000029'::uuid, 'Carl', 'Price', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002a'::uuid, 'Martha', 'Scott', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002b'::uuid, 'Albert', 'Rogers', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002c'::uuid, 'Virginia', 'Henderson', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002d'::uuid, 'Harry', 'Hill', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002e'::uuid, 'Doris', 'Wood', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002f'::uuid, 'Raymond', 'James', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000030'::uuid, 'Betty', 'Crawford', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000031'::uuid, 'Samuel', 'Reed', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000032'::uuid, 'Dorothy', 'Bell', 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Re-seed emails as EMAIL contact points (person email columns removed).
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000001'::uuid, 'EMAIL', 'marcus.patterson@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000001'::uuid AND contact_type='EMAIL' AND value='marcus.patterson@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000002'::uuid, 'EMAIL', 'jennifer.holloway@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000002'::uuid AND contact_type='EMAIL' AND value='jennifer.holloway@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000003'::uuid, 'EMAIL', 'robert.castillo@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000003'::uuid AND contact_type='EMAIL' AND value='robert.castillo@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000004'::uuid, 'EMAIL', 'angela.freeman@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000004'::uuid AND contact_type='EMAIL' AND value='angela.freeman@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000005'::uuid, 'EMAIL', 'derek.washington@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000005'::uuid AND contact_type='EMAIL' AND value='derek.washington@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000006'::uuid, 'EMAIL', 'patricia.simmons@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000006'::uuid AND contact_type='EMAIL' AND value='patricia.simmons@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000007'::uuid, 'EMAIL', 'kevin.thornton@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000007'::uuid AND contact_type='EMAIL' AND value='kevin.thornton@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000008'::uuid, 'EMAIL', 'linda.guerrero@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000008'::uuid AND contact_type='EMAIL' AND value='linda.guerrero@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000009'::uuid, 'EMAIL', 'james.caldwell@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000009'::uuid AND contact_type='EMAIL' AND value='james.caldwell@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000000a'::uuid, 'EMAIL', 'tanya.robinson@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000000a'::uuid AND contact_type='EMAIL' AND value='tanya.robinson@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000000b'::uuid, 'EMAIL', 'michael.owens@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000000b'::uuid AND contact_type='EMAIL' AND value='michael.owens@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000000c'::uuid, 'EMAIL', 'cheryl.hawkins@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000000c'::uuid AND contact_type='EMAIL' AND value='cheryl.hawkins@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000000d'::uuid, 'EMAIL', 'ronald.jenkins@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000000d'::uuid AND contact_type='EMAIL' AND value='ronald.jenkins@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000000e'::uuid, 'EMAIL', 'denise.foster@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000000e'::uuid AND contact_type='EMAIL' AND value='denise.foster@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000000f'::uuid, 'EMAIL', 'anthony.bryant@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000000f'::uuid AND contact_type='EMAIL' AND value='anthony.bryant@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000010'::uuid, 'EMAIL', 'brenda.coleman@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000010'::uuid AND contact_type='EMAIL' AND value='brenda.coleman@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000011'::uuid, 'EMAIL', 'steven.gardner@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000011'::uuid AND contact_type='EMAIL' AND value='steven.gardner@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000012'::uuid, 'EMAIL', 'nicole.harrison@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000012'::uuid AND contact_type='EMAIL' AND value='nicole.harrison@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000013'::uuid, 'EMAIL', 'gary.alexander@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000013'::uuid AND contact_type='EMAIL' AND value='gary.alexander@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000014'::uuid, 'EMAIL', 'carolyn.mitchell@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000014'::uuid AND contact_type='EMAIL' AND value='carolyn.mitchell@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000015'::uuid, 'EMAIL', 'timothy.dixon@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000015'::uuid AND contact_type='EMAIL' AND value='timothy.dixon@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000016'::uuid, 'EMAIL', 'sandra.reeves@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000016'::uuid AND contact_type='EMAIL' AND value='sandra.reeves@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000017'::uuid, 'EMAIL', 'walter.hughes@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000017'::uuid AND contact_type='EMAIL' AND value='walter.hughes@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000018'::uuid, 'EMAIL', 'pamela.lewis@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000018'::uuid AND contact_type='EMAIL' AND value='pamela.lewis@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000019'::uuid, 'EMAIL', 'larry.peterson@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000019'::uuid AND contact_type='EMAIL' AND value='larry.peterson@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000001a'::uuid, 'EMAIL', 'deborah.barnes@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000001a'::uuid AND contact_type='EMAIL' AND value='deborah.barnes@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000001b'::uuid, 'EMAIL', 'frank.murphy@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000001b'::uuid AND contact_type='EMAIL' AND value='frank.murphy@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000001c'::uuid, 'EMAIL', 'sharon.powell@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000001c'::uuid AND contact_type='EMAIL' AND value='sharon.powell@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000001d'::uuid, 'EMAIL', 'raymond.bailey@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000001d'::uuid AND contact_type='EMAIL' AND value='raymond.bailey@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000001e'::uuid, 'EMAIL', 'cynthia.ross@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000001e'::uuid AND contact_type='EMAIL' AND value='cynthia.ross@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000001f'::uuid, 'EMAIL', 'jose.rivera@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000001f'::uuid AND contact_type='EMAIL' AND value='jose.rivera@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000020'::uuid, 'EMAIL', 'gloria.turner@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000020'::uuid AND contact_type='EMAIL' AND value='gloria.turner@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000021'::uuid, 'EMAIL', 'douglas.stewart@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000021'::uuid AND contact_type='EMAIL' AND value='douglas.stewart@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000022'::uuid, 'EMAIL', 'shirley.flores@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000022'::uuid AND contact_type='EMAIL' AND value='shirley.flores@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000023'::uuid, 'EMAIL', 'henry.griffin@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000023'::uuid AND contact_type='EMAIL' AND value='henry.griffin@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000024'::uuid, 'EMAIL', 'marie.evans@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000024'::uuid AND contact_type='EMAIL' AND value='marie.evans@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000025'::uuid, 'EMAIL', 'bruce.king@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000025'::uuid AND contact_type='EMAIL' AND value='bruce.king@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000026'::uuid, 'EMAIL', 'wanda.sanchez@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000026'::uuid AND contact_type='EMAIL' AND value='wanda.sanchez@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000027'::uuid, 'EMAIL', 'keith.ward@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000027'::uuid AND contact_type='EMAIL' AND value='keith.ward@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000028'::uuid, 'EMAIL', 'phyllis.long@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000028'::uuid AND contact_type='EMAIL' AND value='phyllis.long@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000029'::uuid, 'EMAIL', 'carl.price@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000029'::uuid AND contact_type='EMAIL' AND value='carl.price@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000002a'::uuid, 'EMAIL', 'martha.scott@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000002a'::uuid AND contact_type='EMAIL' AND value='martha.scott@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000002b'::uuid, 'EMAIL', 'albert.rogers@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000002b'::uuid AND contact_type='EMAIL' AND value='albert.rogers@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000002c'::uuid, 'EMAIL', 'virginia.henderson@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000002c'::uuid AND contact_type='EMAIL' AND value='virginia.henderson@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000002d'::uuid, 'EMAIL', 'harry.hill@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000002d'::uuid AND contact_type='EMAIL' AND value='harry.hill@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000002e'::uuid, 'EMAIL', 'doris.wood@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000002e'::uuid AND contact_type='EMAIL' AND value='doris.wood@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-00000000002f'::uuid, 'EMAIL', 'raymond.james@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-00000000002f'::uuid AND contact_type='EMAIL' AND value='raymond.james@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000030'::uuid, 'EMAIL', 'betty.crawford@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000030'::uuid AND contact_type='EMAIL' AND value='betty.crawford@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000031'::uuid, 'EMAIL', 'samuel.reed@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000031'::uuid AND contact_type='EMAIL' AND value='samuel.reed@example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960024-0000-7000-8000-000000000032'::uuid, 'EMAIL', 'dorothy.bell@example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960024-0000-7000-8000-000000000032'::uuid AND contact_type='EMAIL' AND value='dorothy.bell@example.com');

-- =========================================================================
-- Group B: 20 commercial primary contact persons (01960025-*) — contact.person_id in pos-customer
-- =========================================================================

INSERT INTO person (id, first_name, last_name, status, created_at, updated_at)
VALUES
    ('01960025-0000-7000-8000-000000000001'::uuid, 'Greg', 'Whitfield', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000002'::uuid, 'Teresa', 'Mullen', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000003'::uuid, 'Darnell', 'Okafor', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000004'::uuid, 'Brittany', 'Norris', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000005'::uuid, 'Marcus', 'Tillman', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000006'::uuid, 'Christine', 'Walters', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000007'::uuid, 'Donald', 'Frazier', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000008'::uuid, 'Alicia', 'Stephens', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000009'::uuid, 'Keith', 'Burnham', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000a'::uuid, 'Tamara', 'McPherson', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000b'::uuid, 'Wesley', 'Parrish', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000c'::uuid, 'Renee', 'Holt', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000d'::uuid, 'Calvin', 'Dunmore', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000e'::uuid, 'Latasha', 'Gooden', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000f'::uuid, 'Bryan', 'Cantrell', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000010'::uuid, 'Monica', 'Byrd', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000011'::uuid, 'Cedric', 'Blackwell', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000012'::uuid, 'Veronica', 'Pratt', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000013'::uuid, 'Jonathon', 'Culpepper', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000014'::uuid, 'Sheryl', 'Davenport', 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Re-seed emails as EMAIL contact points (person email columns removed).
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-000000000001'::uuid, 'EMAIL', 'g.whitfield@piedmontfreight.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-000000000001'::uuid AND contact_type='EMAIL' AND value='g.whitfield@piedmontfreight.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-000000000002'::uuid, 'EMAIL', 't.mullen@carolinaconcrete.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-000000000002'::uuid AND contact_type='EMAIL' AND value='t.mullen@carolinaconcrete.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-000000000003'::uuid, 'EMAIL', 'd.okafor@qcwaste.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-000000000003'::uuid AND contact_type='EMAIL' AND value='d.okafor@qcwaste.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-000000000004'::uuid, 'EMAIL', 'b.norris@blueridgelandscaping.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-000000000004'::uuid AND contact_type='EMAIL' AND value='b.norris@blueridgelandscaping.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-000000000005'::uuid, 'EMAIL', 'm.tillman@tarheellogistics.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-000000000005'::uuid AND contact_type='EMAIL' AND value='m.tillman@tarheellogistics.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-000000000006'::uuid, 'EMAIL', 'c.walters@meckplumbing.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-000000000006'::uuid AND contact_type='EMAIL' AND value='c.walters@meckplumbing.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-000000000007'::uuid, 'EMAIL', 'd.frazier@piedmontreadymix.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-000000000007'::uuid AND contact_type='EMAIL' AND value='d.frazier@piedmontreadymix.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-000000000008'::uuid, 'EMAIL', 'a.stephens@carolinapower.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-000000000008'::uuid AND contact_type='EMAIL' AND value='a.stephens@carolinapower.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-000000000009'::uuid, 'EMAIL', 'k.burnham@bluestoneaggregate.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-000000000009'::uuid AND contact_type='EMAIL' AND value='k.burnham@bluestoneaggregate.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-00000000000a'::uuid, 'EMAIL', 't.mcpherson@cabarruscleaning.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-00000000000a'::uuid AND contact_type='EMAIL' AND value='t.mcpherson@cabarruscleaning.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-00000000000b'::uuid, 'EMAIL', 'w.parrish@sedelivery.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-00000000000b'::uuid AND contact_type='EMAIL' AND value='w.parrish@sedelivery.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-00000000000c'::uuid, 'EMAIL', 'r.holt@carolinascrane.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-00000000000c'::uuid AND contact_type='EMAIL' AND value='r.holt@carolinascrane.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-00000000000d'::uuid, 'EMAIL', 'c.dunmore@lknpropane.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-00000000000d'::uuid AND contact_type='EMAIL' AND value='c.dunmore@lknpropane.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-00000000000e'::uuid, 'EMAIL', 'l.gooden@rowanroad.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-00000000000e'::uuid AND contact_type='EMAIL' AND value='l.gooden@rowanroad.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-00000000000f'::uuid, 'EMAIL', 'b.cantrell@carolinafresh.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-00000000000f'::uuid AND contact_type='EMAIL' AND value='b.cantrell@carolinafresh.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-000000000010'::uuid, 'EMAIL', 'm.byrd@piedmontmetals.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-000000000010'::uuid AND contact_type='EMAIL' AND value='m.byrd@piedmontmetals.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-000000000011'::uuid, 'EMAIL', 'c.blackwell@uniongrading.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-000000000011'::uuid AND contact_type='EMAIL' AND value='c.blackwell@uniongrading.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-000000000012'::uuid, 'EMAIL', 'v.pratt@carolinaseptic.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-000000000012'::uuid AND contact_type='EMAIL' AND value='v.pratt@carolinaseptic.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-000000000013'::uuid, 'EMAIL', 'j.culpepper@mecktree.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-000000000013'::uuid AND contact_type='EMAIL' AND value='j.culpepper@mecktree.example.com');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960025-0000-7000-8000-000000000014'::uuid, 'EMAIL', 's.davenport@highlandmoving.example.com', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960025-0000-7000-8000-000000000014'::uuid AND contact_type='EMAIL' AND value='s.davenport@highlandmoving.example.com');

-- =========================================================================
-- Group C (REMOVED): the 20 commercial billing contacts (01960026-*) were
-- duplicate persons of the 01960025-* primary contacts. They are deleted by
-- migration V5__remove_billing_contact_duplicates.sql and no longer seeded; the
-- matching pos-customer person_party rows are removed by pos-customer V9.
-- =========================================================================

-- ADR-0015: the person table also holds customer individuals (Group A) and
-- commercial contacts (Group B), which are NOT employees. EmployeeStatus is an
-- employment lifecycle value and must not be set on non-employees, otherwise the
-- people directory's employee filters surface them. employee_number is the
-- authoritative employee discriminator, so clear status wherever it is absent.
UPDATE person
   SET status = NULL,
       status_effective_at = NULL
 WHERE employee_number IS NULL
   AND status IS NOT NULL;

-- user_person_links (guarded by person table existence; person rows inserted above)
DO $$
BEGIN
    IF to_regclass('public.person') IS NOT NULL THEN
        INSERT INTO user_person_links (id, user_id, person_id, link_type, status, created_at, created_by)
        VALUES
            ('01960012-0000-7000-8000-000000000001'::uuid, '01960010-0000-7000-8000-000000000001'::uuid, '01960011-0000-7000-8000-000000000001'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000002'::uuid, '01960010-0000-7000-8000-000000000002'::uuid, '01960011-0000-7000-8000-000000000002'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000003'::uuid, '01960010-0000-7000-8000-000000000003'::uuid, '01960011-0000-7000-8000-000000000003'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000004'::uuid, '01960010-0000-7000-8000-000000000004'::uuid, '01960011-0000-7000-8000-000000000004'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000005'::uuid, '01960010-0000-7000-8000-000000000005'::uuid, '01960011-0000-7000-8000-000000000005'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000006'::uuid, '01960010-0000-7000-8000-000000000006'::uuid, '01960011-0000-7000-8000-000000000006'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000007'::uuid, '01960010-0000-7000-8000-000000000007'::uuid, '01960011-0000-7000-8000-000000000007'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000008'::uuid, '01960010-0000-7000-8000-000000000008'::uuid, '01960011-0000-7000-8000-000000000008'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000009'::uuid, '01960010-0000-7000-8000-000000000009'::uuid, '01960011-0000-7000-8000-000000000009'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-00000000000a'::uuid, '01960010-0000-7000-8000-00000000000a'::uuid, '01960011-0000-7000-8000-00000000000a'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-00000000000b'::uuid, '01960010-0000-7000-8000-00000000000b'::uuid, '01960011-0000-7000-8000-00000000000b'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-00000000000c'::uuid, '01960010-0000-7000-8000-00000000000c'::uuid, '01960011-0000-7000-8000-00000000000c'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-00000000000d'::uuid, '01960010-0000-7000-8000-00000000000d'::uuid, '01960011-0000-7000-8000-00000000000d'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-00000000000e'::uuid, '01960010-0000-7000-8000-00000000000e'::uuid, '01960011-0000-7000-8000-00000000000e'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-00000000000f'::uuid, '01960010-0000-7000-8000-00000000000f'::uuid, '01960011-0000-7000-8000-00000000000f'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000010'::uuid, '01960010-0000-7000-8000-000000000010'::uuid, '01960011-0000-7000-8000-000000000010'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator')
        ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

-- person_location_assignment — unconditional idempotent seed.
-- location_id has no FK, so these are NOT guarded on location existence: a guarded
-- repeatable that ran before pos-location seeded skipped every row and never retried
-- (Flyway repeatables only re-run on checksum change), leaving staff unassigned in
-- every location. Unconditional + ON CONFLICT (id) DO NOTHING is idempotent and
-- ordering-independent, so fresh environments populate regardless of seed order.
INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
VALUES
    ('01960013-0000-7000-8000-000000000001'::uuid, '01960011-0000-7000-8000-000000000001'::uuid, '01960003-0000-7000-8000-000000000005'::uuid, 'SYSTEM_ADMINISTRATOR', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000002'::uuid, '01960011-0000-7000-8000-000000000002'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'LOCATION_MANAGER', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000003'::uuid, '01960011-0000-7000-8000-000000000003'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'DISPATCHER', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000004'::uuid, '01960011-0000-7000-8000-000000000004'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'DISPATCHER', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000005'::uuid, '01960011-0000-7000-8000-000000000005'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000006'::uuid, '01960011-0000-7000-8000-000000000006'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000007'::uuid, '01960011-0000-7000-8000-000000000007'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000008'::uuid, '01960011-0000-7000-8000-000000000008'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000009'::uuid, '01960011-0000-7000-8000-000000000009'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-00000000000a'::uuid, '01960011-0000-7000-8000-00000000000a'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-00000000000b'::uuid, '01960011-0000-7000-8000-00000000000b'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-00000000000c'::uuid, '01960011-0000-7000-8000-00000000000c'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'SERVICE_ADVISOR', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-00000000000d'::uuid, '01960011-0000-7000-8000-00000000000d'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'SERVICE_ADVISOR', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-00000000000e'::uuid, '01960011-0000-7000-8000-00000000000e'::uuid, '01960003-0000-7000-8000-000000000005'::uuid, 'ACCOUNTING_ASSOCIATE', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-00000000000f'::uuid, '01960011-0000-7000-8000-00000000000f'::uuid, '01960003-0000-7000-8000-000000000005'::uuid, 'ACCOUNTING_ASSOCIATE', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000010'::uuid, '01960011-0000-7000-8000-000000000010'::uuid, '01960003-0000-7000-8000-000000000005'::uuid, 'ACCOUNT_MANAGER', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator')
ON CONFLICT (id) DO NOTHING;

-- =========================================================================
-- Person contact points (mirrors pos-customer contact_point for the 20
-- commercial primary contacts, 01960025-*). pos-people is SoT for contacts.
--   01960030-*: email points   01960031-*: phone points
-- =========================================================================
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
VALUES
    ('01960030-0000-7000-8000-000000000001'::uuid, '01960025-0000-7000-8000-000000000001'::uuid, 'EMAIL', 'g.whitfield@piedmontfreight.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-000000000001'::uuid, '01960025-0000-7000-8000-000000000001'::uuid, 'PHONE_WORK', '704-555-3001', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-000000000002'::uuid, '01960025-0000-7000-8000-000000000002'::uuid, 'EMAIL', 't.mullen@carolinaconcrete.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-000000000002'::uuid, '01960025-0000-7000-8000-000000000002'::uuid, 'PHONE_WORK', '704-555-3002', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-000000000003'::uuid, '01960025-0000-7000-8000-000000000003'::uuid, 'EMAIL', 'd.okafor@qcwaste.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-000000000003'::uuid, '01960025-0000-7000-8000-000000000003'::uuid, 'PHONE_WORK', '704-555-3003', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-000000000004'::uuid, '01960025-0000-7000-8000-000000000004'::uuid, 'EMAIL', 'b.norris@blueridgelandscaping.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-000000000004'::uuid, '01960025-0000-7000-8000-000000000004'::uuid, 'PHONE_WORK', '704-555-3004', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-000000000005'::uuid, '01960025-0000-7000-8000-000000000005'::uuid, 'EMAIL', 'm.tillman@tarheellogistics.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-000000000005'::uuid, '01960025-0000-7000-8000-000000000005'::uuid, 'PHONE_WORK', '704-555-3005', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-000000000006'::uuid, '01960025-0000-7000-8000-000000000006'::uuid, 'EMAIL', 'c.walters@meckplumbing.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-000000000006'::uuid, '01960025-0000-7000-8000-000000000006'::uuid, 'PHONE_WORK', '704-555-3006', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-000000000007'::uuid, '01960025-0000-7000-8000-000000000007'::uuid, 'EMAIL', 'd.frazier@piedmontreadymix.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-000000000007'::uuid, '01960025-0000-7000-8000-000000000007'::uuid, 'PHONE_WORK', '704-555-3007', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-000000000008'::uuid, '01960025-0000-7000-8000-000000000008'::uuid, 'EMAIL', 'a.stephens@carolinapower.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-000000000008'::uuid, '01960025-0000-7000-8000-000000000008'::uuid, 'PHONE_WORK', '704-555-3008', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-000000000009'::uuid, '01960025-0000-7000-8000-000000000009'::uuid, 'EMAIL', 'k.burnham@bluestoneaggregate.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-000000000009'::uuid, '01960025-0000-7000-8000-000000000009'::uuid, 'PHONE_WORK', '704-555-3009', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-00000000000a'::uuid, '01960025-0000-7000-8000-00000000000a'::uuid, 'EMAIL', 't.mcpherson@cabarruscleaning.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-00000000000a'::uuid, '01960025-0000-7000-8000-00000000000a'::uuid, 'PHONE_WORK', '704-555-3010', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-00000000000b'::uuid, '01960025-0000-7000-8000-00000000000b'::uuid, 'EMAIL', 'w.parrish@sedelivery.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-00000000000b'::uuid, '01960025-0000-7000-8000-00000000000b'::uuid, 'PHONE_WORK', '980-555-3011', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-00000000000c'::uuid, '01960025-0000-7000-8000-00000000000c'::uuid, 'EMAIL', 'r.holt@carolinascrane.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-00000000000c'::uuid, '01960025-0000-7000-8000-00000000000c'::uuid, 'PHONE_WORK', '704-555-3012', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-00000000000d'::uuid, '01960025-0000-7000-8000-00000000000d'::uuid, 'EMAIL', 'c.dunmore@lknpropane.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-00000000000d'::uuid, '01960025-0000-7000-8000-00000000000d'::uuid, 'PHONE_WORK', '704-555-3013', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-00000000000e'::uuid, '01960025-0000-7000-8000-00000000000e'::uuid, 'EMAIL', 'l.gooden@rowanroad.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-00000000000e'::uuid, '01960025-0000-7000-8000-00000000000e'::uuid, 'PHONE_WORK', '704-555-3014', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-00000000000f'::uuid, '01960025-0000-7000-8000-00000000000f'::uuid, 'EMAIL', 'b.cantrell@carolinafresh.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-00000000000f'::uuid, '01960025-0000-7000-8000-00000000000f'::uuid, 'PHONE_WORK', '980-555-3015', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-000000000010'::uuid, '01960025-0000-7000-8000-000000000010'::uuid, 'EMAIL', 'm.byrd@piedmontmetals.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-000000000010'::uuid, '01960025-0000-7000-8000-000000000010'::uuid, 'PHONE_WORK', '704-555-3016', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-000000000011'::uuid, '01960025-0000-7000-8000-000000000011'::uuid, 'EMAIL', 'c.blackwell@uniongrading.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-000000000011'::uuid, '01960025-0000-7000-8000-000000000011'::uuid, 'PHONE_WORK', '704-555-3017', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-000000000012'::uuid, '01960025-0000-7000-8000-000000000012'::uuid, 'EMAIL', 'v.pratt@carolinaseptic.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-000000000012'::uuid, '01960025-0000-7000-8000-000000000012'::uuid, 'PHONE_WORK', '704-555-3018', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-000000000013'::uuid, '01960025-0000-7000-8000-000000000013'::uuid, 'EMAIL', 'j.culpepper@mecktree.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-000000000013'::uuid, '01960025-0000-7000-8000-000000000013'::uuid, 'PHONE_WORK', '704-555-3019', true, NOW(), NOW()),
    ('01960030-0000-7000-8000-000000000014'::uuid, '01960025-0000-7000-8000-000000000014'::uuid, 'EMAIL', 's.davenport@highlandmoving.example.com', true, NOW(), NOW()),
    ('01960031-0000-7000-8000-000000000014'::uuid, '01960025-0000-7000-8000-000000000014'::uuid, 'PHONE_WORK', '980-555-3020', true, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- =========================================================================
-- OPERATIONAL FILL: additional staffing so every service center carries the
-- full shop-operational role set (LOCATION_MANAGER, DISPATCHER,
-- SERVICE_ADVISOR, TECHNICIAN) and at least one technician per bay (±2).
-- Corporate roles (SYSTEM_ADMINISTRATOR, ACCOUNTING_*) remain at CORP-HQ-001.
--
-- Technician sizing per location (target == capacity, within the ±2 band):
--   CLT-MAIN-001  : 8 bays          → 8 techs (3 existing + 5 here)
--   CLT-SOUTH-001 : 7 bays          → 7 techs (2 existing + 5 here)
--   CLT-NORTH-001 : 6 bays          → 6 techs (2 existing + 4 here)
--   CLT-MOB-HUB-001: 2 mobile units → 2 techs (0 existing + 2 here)
--
-- New employees EMP-0017..EMP-0039 (person ids 01960011-*-0011..0027).
-- Appended fill — idempotent via ON CONFLICT; does not alter rows above.
-- These are staffing records only; no security users / logins are seeded.
-- =========================================================================

INSERT INTO person (id, first_name, last_name, employee_number, status, hire_date, created_at, updated_at)
VALUES
    ('01960011-0000-7000-8000-000000000011'::uuid, 'Hector', 'Alvarez', 'EMP-0017', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000012'::uuid, 'Naomi', 'Ford', 'EMP-0018', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000013'::uuid, 'Trevor', 'Quinn', 'EMP-0019', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000014'::uuid, 'Camille', 'Boyd', 'EMP-0020', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000015'::uuid, 'Andre', 'Foster', 'EMP-0021', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000016'::uuid, 'Lila', 'Montgomery', 'EMP-0022', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000017'::uuid, 'Desmond', 'Pace', 'EMP-0023', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000018'::uuid, 'Brooke', 'Hadley', 'EMP-0024', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000019'::uuid, 'Felix', 'Romano', 'EMP-0025', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000001a'::uuid, 'Gina', 'Vaughn', 'EMP-0026', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000001b'::uuid, 'Omar', 'Haddad', 'EMP-0027', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000001c'::uuid, 'Sierra', 'Lowe', 'EMP-0028', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000001d'::uuid, 'Russell', 'Pike', 'EMP-0029', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000001e'::uuid, 'Maya', 'Devlin', 'EMP-0030', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000001f'::uuid, 'Caleb', 'Frost', 'EMP-0031', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000020'::uuid, 'Yvonne', 'Marsh', 'EMP-0032', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000021'::uuid, 'Bernard', 'Cole', 'EMP-0033', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000022'::uuid, 'Gloria', 'Mensah', 'EMP-0034', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000023'::uuid, 'Victor', 'Salazar', 'EMP-0035', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000024'::uuid, 'Renee', 'Albright', 'EMP-0036', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000025'::uuid, 'Curtis', 'Benton', 'EMP-0037', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000026'::uuid, 'Paula', 'Knight', 'EMP-0038', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000027'::uuid, 'Simon', 'Hayes', 'EMP-0039', 'ACTIVE', CURRENT_DATE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Re-seed emails as EMAIL contact points (person email columns removed).
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000011'::uuid, 'EMAIL', 'hector.alvarez@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000011'::uuid AND contact_type='EMAIL' AND value='hector.alvarez@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000012'::uuid, 'EMAIL', 'naomi.ford@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000012'::uuid AND contact_type='EMAIL' AND value='naomi.ford@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000013'::uuid, 'EMAIL', 'trevor.quinn@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000013'::uuid AND contact_type='EMAIL' AND value='trevor.quinn@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000014'::uuid, 'EMAIL', 'camille.boyd@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000014'::uuid AND contact_type='EMAIL' AND value='camille.boyd@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000015'::uuid, 'EMAIL', 'andre.foster@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000015'::uuid AND contact_type='EMAIL' AND value='andre.foster@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000016'::uuid, 'EMAIL', 'lila.montgomery@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000016'::uuid AND contact_type='EMAIL' AND value='lila.montgomery@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000017'::uuid, 'EMAIL', 'desmond.pace@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000017'::uuid AND contact_type='EMAIL' AND value='desmond.pace@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000018'::uuid, 'EMAIL', 'brooke.hadley@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000018'::uuid AND contact_type='EMAIL' AND value='brooke.hadley@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000019'::uuid, 'EMAIL', 'felix.romano@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000019'::uuid AND contact_type='EMAIL' AND value='felix.romano@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-00000000001a'::uuid, 'EMAIL', 'gina.vaughn@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-00000000001a'::uuid AND contact_type='EMAIL' AND value='gina.vaughn@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-00000000001b'::uuid, 'EMAIL', 'omar.haddad@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-00000000001b'::uuid AND contact_type='EMAIL' AND value='omar.haddad@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-00000000001c'::uuid, 'EMAIL', 'sierra.lowe@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-00000000001c'::uuid AND contact_type='EMAIL' AND value='sierra.lowe@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-00000000001d'::uuid, 'EMAIL', 'russell.pike@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-00000000001d'::uuid AND contact_type='EMAIL' AND value='russell.pike@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-00000000001e'::uuid, 'EMAIL', 'maya.devlin@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-00000000001e'::uuid AND contact_type='EMAIL' AND value='maya.devlin@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-00000000001f'::uuid, 'EMAIL', 'caleb.frost@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-00000000001f'::uuid AND contact_type='EMAIL' AND value='caleb.frost@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000020'::uuid, 'EMAIL', 'yvonne.marsh@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000020'::uuid AND contact_type='EMAIL' AND value='yvonne.marsh@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000021'::uuid, 'EMAIL', 'bernard.cole@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000021'::uuid AND contact_type='EMAIL' AND value='bernard.cole@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000022'::uuid, 'EMAIL', 'gloria.mensah@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000022'::uuid AND contact_type='EMAIL' AND value='gloria.mensah@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000023'::uuid, 'EMAIL', 'victor.salazar@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000023'::uuid AND contact_type='EMAIL' AND value='victor.salazar@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000024'::uuid, 'EMAIL', 'renee.albright@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000024'::uuid AND contact_type='EMAIL' AND value='renee.albright@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000025'::uuid, 'EMAIL', 'curtis.benton@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000025'::uuid AND contact_type='EMAIL' AND value='curtis.benton@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000026'::uuid, 'EMAIL', 'paula.knight@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000026'::uuid AND contact_type='EMAIL' AND value='paula.knight@durion.internal');
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '01960011-0000-7000-8000-000000000027'::uuid, 'EMAIL', 'simon.hayes@durion.internal', TRUE, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM person_contact_point WHERE person_id='01960011-0000-7000-8000-000000000027'::uuid AND contact_type='EMAIL' AND value='simon.hayes@durion.internal');

-- person_location_assignment for the additional staff (guarded by location existence).
-- effective_from = CURRENT_DATE keeps each row distinct under the
-- (person_id, location_id, role, effective_from) unique key.
DO $$
BEGIN
    IF to_regclass('public.person') IS NOT NULL
       AND to_regclass('public.location') IS NOT NULL
    THEN
        INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
        SELECT v.id, v.person_id, v.location_id, v.role, TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
        FROM (
            VALUES
                -- CLT-MAIN-001 technicians
                ('01960013-0000-7000-8000-000000000011'::uuid, '01960011-0000-7000-8000-000000000011'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000012'::uuid, '01960011-0000-7000-8000-000000000012'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000013'::uuid, '01960011-0000-7000-8000-000000000013'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000014'::uuid, '01960011-0000-7000-8000-000000000014'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000015'::uuid, '01960011-0000-7000-8000-000000000015'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN'),
                -- CLT-SOUTH-001 technicians
                ('01960013-0000-7000-8000-000000000016'::uuid, '01960011-0000-7000-8000-000000000016'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000017'::uuid, '01960011-0000-7000-8000-000000000017'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000018'::uuid, '01960011-0000-7000-8000-000000000018'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000019'::uuid, '01960011-0000-7000-8000-000000000019'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-00000000001a'::uuid, '01960011-0000-7000-8000-00000000001a'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN'),
                -- CLT-NORTH-001 technicians
                ('01960013-0000-7000-8000-00000000001b'::uuid, '01960011-0000-7000-8000-00000000001b'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-00000000001c'::uuid, '01960011-0000-7000-8000-00000000001c'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-00000000001d'::uuid, '01960011-0000-7000-8000-00000000001d'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-00000000001e'::uuid, '01960011-0000-7000-8000-00000000001e'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'TECHNICIAN'),
                -- CLT-MOB-HUB-001 technicians
                ('01960013-0000-7000-8000-00000000001f'::uuid, '01960011-0000-7000-8000-00000000001f'::uuid, '01960003-0000-7000-8000-000000000004'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000020'::uuid, '01960011-0000-7000-8000-000000000020'::uuid, '01960003-0000-7000-8000-000000000004'::uuid, 'TECHNICIAN'),
                -- LOCATION_MANAGER coverage
                ('01960013-0000-7000-8000-000000000021'::uuid, '01960011-0000-7000-8000-000000000021'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'LOCATION_MANAGER'),
                ('01960013-0000-7000-8000-000000000022'::uuid, '01960011-0000-7000-8000-000000000022'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'LOCATION_MANAGER'),
                ('01960013-0000-7000-8000-000000000023'::uuid, '01960011-0000-7000-8000-000000000023'::uuid, '01960003-0000-7000-8000-000000000004'::uuid, 'LOCATION_MANAGER'),
                -- DISPATCHER coverage
                ('01960013-0000-7000-8000-000000000024'::uuid, '01960011-0000-7000-8000-000000000024'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'DISPATCHER'),
                ('01960013-0000-7000-8000-000000000025'::uuid, '01960011-0000-7000-8000-000000000025'::uuid, '01960003-0000-7000-8000-000000000004'::uuid, 'DISPATCHER'),
                -- SERVICE_ADVISOR coverage
                ('01960013-0000-7000-8000-000000000026'::uuid, '01960011-0000-7000-8000-000000000026'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'SERVICE_ADVISOR'),
                ('01960013-0000-7000-8000-000000000027'::uuid, '01960011-0000-7000-8000-000000000027'::uuid, '01960003-0000-7000-8000-000000000004'::uuid, 'SERVICE_ADVISOR')
        ) AS v(id, person_id, location_id, role)
        WHERE EXISTS (SELECT 1 FROM public.location l WHERE l.id = v.location_id)
        ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;
