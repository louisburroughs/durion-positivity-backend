-- Repeatable seed migration for pos-people operational data (HR only since #875:
-- person identity rows live in pos-people-contact; this file seeds the ext_* replicas
-- for dev bootstrap plus the employee/assignment rows pos-people owns).
-- Re-run marker 2026-06-24: bump checksum so Flyway re-applies this repeatable.
-- employee_location_assignment rows are guarded by `WHERE EXISTS (location id=...)`; on the
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

-- person rows (identity only; employment lives on employee)
-- ext_people_contact_person replica bootstrap for the 39 staff persons (dev/docker only:
-- pos-people-contact seeds the same ids as the authority, so replica and owner agree;
-- deployed environments are seeded by the people-contact.events.v1 manifest/replay flow).
INSERT INTO ext_people_contact_person (person_id, first_name, last_name, primary_email, primary_phone, secondary_phone, aggregate_version, updated_at)
VALUES
    ('01960011-0000-7000-8000-000000000001'::uuid, 'Marcus', 'Webb', 'marcus.webb@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000002'::uuid, 'Diana', 'Rowe', 'diana.rowe@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000003'::uuid, 'Terrence', 'Blake', 'terrence.blake@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000004'::uuid, 'Sandra', 'Cruz', 'sandra.cruz@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000005'::uuid, 'Kyle', 'Brennan', 'kyle.brennan@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000006'::uuid, 'DeShawn', 'Morris', 'deshawn.morris@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000007'::uuid, 'Carlos', 'Ruiz', 'carlos.ruiz@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000008'::uuid, 'Amber', 'Nguyen', 'amber.nguyen@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000009'::uuid, 'Eddie', 'Vasquez', 'eddie.vasquez@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-00000000000a'::uuid, 'Priya', 'Patel', 'priya.patel@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-00000000000b'::uuid, 'James', 'Okafor', 'james.okafor@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-00000000000c'::uuid, 'Rachel', 'Kim', 'rachel.kim@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-00000000000d'::uuid, 'Tyrone', 'Williams', 'tyrone.williams@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-00000000000e'::uuid, 'Olivia', 'Chen', 'olivia.chen@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-00000000000f'::uuid, 'Harold', 'Sanders', 'harold.sanders@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000010'::uuid, 'Irene', 'Torres', 'irene.torres@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000011'::uuid, 'Hector', 'Alvarez', 'hector.alvarez@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000012'::uuid, 'Naomi', 'Ford', 'naomi.ford@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000013'::uuid, 'Trevor', 'Quinn', 'trevor.quinn@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000014'::uuid, 'Camille', 'Boyd', 'camille.boyd@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000015'::uuid, 'Andre', 'Foster', 'andre.foster@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000016'::uuid, 'Lila', 'Montgomery', 'lila.montgomery@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000017'::uuid, 'Desmond', 'Pace', 'desmond.pace@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000018'::uuid, 'Brooke', 'Hadley', 'brooke.hadley@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000019'::uuid, 'Felix', 'Romano', 'felix.romano@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-00000000001a'::uuid, 'Gina', 'Vaughn', 'gina.vaughn@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-00000000001b'::uuid, 'Omar', 'Haddad', 'omar.haddad@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-00000000001c'::uuid, 'Sierra', 'Lowe', 'sierra.lowe@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-00000000001d'::uuid, 'Russell', 'Pike', 'russell.pike@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-00000000001e'::uuid, 'Maya', 'Devlin', 'maya.devlin@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-00000000001f'::uuid, 'Caleb', 'Frost', 'caleb.frost@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000020'::uuid, 'Yvonne', 'Marsh', 'yvonne.marsh@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000021'::uuid, 'Bernard', 'Cole', 'bernard.cole@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000022'::uuid, 'Gloria', 'Mensah', 'gloria.mensah@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000023'::uuid, 'Victor', 'Salazar', 'victor.salazar@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000024'::uuid, 'Renee', 'Albright', 'renee.albright@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000025'::uuid, 'Curtis', 'Benton', 'curtis.benton@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000026'::uuid, 'Paula', 'Knight', 'paula.knight@durion.internal', NULL, NULL, 0, NOW()),
    ('01960011-0000-7000-8000-000000000027'::uuid, 'Simon', 'Hayes', 'simon.hayes@durion.internal', NULL, NULL, 0, NOW())
ON CONFLICT (person_id) DO NOTHING;

-- employee rows (employment for the 39 staff persons)
INSERT INTO employee (id, person_id, employee_number, status, hire_date, status_effective_at, created_at, updated_at)
VALUES
    ('01960014-0000-7000-8000-000000000001'::uuid, '01960011-0000-7000-8000-000000000001'::uuid, 'EMP-0001', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000002'::uuid, '01960011-0000-7000-8000-000000000002'::uuid, 'EMP-0002', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000003'::uuid, '01960011-0000-7000-8000-000000000003'::uuid, 'EMP-0003', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000004'::uuid, '01960011-0000-7000-8000-000000000004'::uuid, 'EMP-0004', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000005'::uuid, '01960011-0000-7000-8000-000000000005'::uuid, 'EMP-0005', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000006'::uuid, '01960011-0000-7000-8000-000000000006'::uuid, 'EMP-0006', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000007'::uuid, '01960011-0000-7000-8000-000000000007'::uuid, 'EMP-0007', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000008'::uuid, '01960011-0000-7000-8000-000000000008'::uuid, 'EMP-0008', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000009'::uuid, '01960011-0000-7000-8000-000000000009'::uuid, 'EMP-0009', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-00000000000a'::uuid, '01960011-0000-7000-8000-00000000000a'::uuid, 'EMP-0010', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-00000000000b'::uuid, '01960011-0000-7000-8000-00000000000b'::uuid, 'EMP-0011', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-00000000000c'::uuid, '01960011-0000-7000-8000-00000000000c'::uuid, 'EMP-0012', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-00000000000d'::uuid, '01960011-0000-7000-8000-00000000000d'::uuid, 'EMP-0013', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-00000000000e'::uuid, '01960011-0000-7000-8000-00000000000e'::uuid, 'EMP-0014', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-00000000000f'::uuid, '01960011-0000-7000-8000-00000000000f'::uuid, 'EMP-0015', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000010'::uuid, '01960011-0000-7000-8000-000000000010'::uuid, 'EMP-0016', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000011'::uuid, '01960011-0000-7000-8000-000000000011'::uuid, 'EMP-0017', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000012'::uuid, '01960011-0000-7000-8000-000000000012'::uuid, 'EMP-0018', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000013'::uuid, '01960011-0000-7000-8000-000000000013'::uuid, 'EMP-0019', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000014'::uuid, '01960011-0000-7000-8000-000000000014'::uuid, 'EMP-0020', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000015'::uuid, '01960011-0000-7000-8000-000000000015'::uuid, 'EMP-0021', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000016'::uuid, '01960011-0000-7000-8000-000000000016'::uuid, 'EMP-0022', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000017'::uuid, '01960011-0000-7000-8000-000000000017'::uuid, 'EMP-0023', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000018'::uuid, '01960011-0000-7000-8000-000000000018'::uuid, 'EMP-0024', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000019'::uuid, '01960011-0000-7000-8000-000000000019'::uuid, 'EMP-0025', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-00000000001a'::uuid, '01960011-0000-7000-8000-00000000001a'::uuid, 'EMP-0026', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-00000000001b'::uuid, '01960011-0000-7000-8000-00000000001b'::uuid, 'EMP-0027', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-00000000001c'::uuid, '01960011-0000-7000-8000-00000000001c'::uuid, 'EMP-0028', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-00000000001d'::uuid, '01960011-0000-7000-8000-00000000001d'::uuid, 'EMP-0029', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-00000000001e'::uuid, '01960011-0000-7000-8000-00000000001e'::uuid, 'EMP-0030', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-00000000001f'::uuid, '01960011-0000-7000-8000-00000000001f'::uuid, 'EMP-0031', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000020'::uuid, '01960011-0000-7000-8000-000000000020'::uuid, 'EMP-0032', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000021'::uuid, '01960011-0000-7000-8000-000000000021'::uuid, 'EMP-0033', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000022'::uuid, '01960011-0000-7000-8000-000000000022'::uuid, 'EMP-0034', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000023'::uuid, '01960011-0000-7000-8000-000000000023'::uuid, 'EMP-0035', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000024'::uuid, '01960011-0000-7000-8000-000000000024'::uuid, 'EMP-0036', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000025'::uuid, '01960011-0000-7000-8000-000000000025'::uuid, 'EMP-0037', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000026'::uuid, '01960011-0000-7000-8000-000000000026'::uuid, 'EMP-0038', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW()),
    ('01960014-0000-7000-8000-000000000027'::uuid, '01960011-0000-7000-8000-000000000027'::uuid, 'EMP-0039', 'ACTIVE', CURRENT_DATE, NOW(), NOW(), NOW())
ON CONFLICT (person_id) DO NOTHING;

-- Re-seed emails as EMAIL contact points (person email columns removed).

-- =========================================================================
-- Group A: 50 customer persons (01960024-*) — person_party.person_id in pos-customer
-- =========================================================================

-- (moved to pos-people-contact: identity rows for customers/commercial contacts, #875)

-- Re-seed emails as EMAIL contact points (person email columns removed).

-- =========================================================================
-- Group B: 20 commercial primary contact persons (01960025-*) — contact.person_id in pos-customer
-- =========================================================================

-- (moved to pos-people-contact: identity rows for customers/commercial contacts, #875)

-- Re-seed emails as EMAIL contact points (person email columns removed).

-- =========================================================================
-- Group C (REMOVED): the 20 commercial billing contacts (01960026-*) were
-- duplicate persons of the 01960025-* primary contacts. They are deleted by
-- migration V5__remove_billing_contact_duplicates.sql and no longer seeded; the
-- matching pos-customer person_party rows are removed by pos-customer V9.
-- =========================================================================


-- ext_people_contact_user_link replica rows (usernames for HR views)
-- ext_people_contact_user_link replica bootstrap (dev/docker only; see note above).
INSERT INTO ext_people_contact_user_link (link_id, person_id, username, status, aggregate_version, updated_at)
VALUES
    ('01960012-0000-7000-8000-000000000001'::uuid, '01960011-0000-7000-8000-000000000001'::uuid, 'marcus.webb', 'ACTIVE', 0, NOW()),
    ('01960012-0000-7000-8000-000000000002'::uuid, '01960011-0000-7000-8000-000000000002'::uuid, 'diana.rowe', 'ACTIVE', 0, NOW()),
    ('01960012-0000-7000-8000-000000000003'::uuid, '01960011-0000-7000-8000-000000000003'::uuid, 'terrence.blake', 'ACTIVE', 0, NOW()),
    ('01960012-0000-7000-8000-000000000004'::uuid, '01960011-0000-7000-8000-000000000004'::uuid, 'sandra.cruz', 'ACTIVE', 0, NOW()),
    ('01960012-0000-7000-8000-000000000005'::uuid, '01960011-0000-7000-8000-000000000005'::uuid, 'kyle.brennan', 'ACTIVE', 0, NOW()),
    ('01960012-0000-7000-8000-000000000006'::uuid, '01960011-0000-7000-8000-000000000006'::uuid, 'deshawn.morris', 'ACTIVE', 0, NOW()),
    ('01960012-0000-7000-8000-000000000007'::uuid, '01960011-0000-7000-8000-000000000007'::uuid, 'carlos.ruiz', 'ACTIVE', 0, NOW()),
    ('01960012-0000-7000-8000-000000000008'::uuid, '01960011-0000-7000-8000-000000000008'::uuid, 'amber.nguyen', 'ACTIVE', 0, NOW()),
    ('01960012-0000-7000-8000-000000000009'::uuid, '01960011-0000-7000-8000-000000000009'::uuid, 'eddie.vasquez', 'ACTIVE', 0, NOW()),
    ('01960012-0000-7000-8000-00000000000a'::uuid, '01960011-0000-7000-8000-00000000000a'::uuid, 'priya.patel', 'ACTIVE', 0, NOW()),
    ('01960012-0000-7000-8000-00000000000b'::uuid, '01960011-0000-7000-8000-00000000000b'::uuid, 'james.okafor', 'ACTIVE', 0, NOW()),
    ('01960012-0000-7000-8000-00000000000c'::uuid, '01960011-0000-7000-8000-00000000000c'::uuid, 'rachel.kim', 'ACTIVE', 0, NOW()),
    ('01960012-0000-7000-8000-00000000000d'::uuid, '01960011-0000-7000-8000-00000000000d'::uuid, 'tyrone.williams', 'ACTIVE', 0, NOW()),
    ('01960012-0000-7000-8000-00000000000e'::uuid, '01960011-0000-7000-8000-00000000000e'::uuid, 'olivia.chen', 'ACTIVE', 0, NOW()),
    ('01960012-0000-7000-8000-00000000000f'::uuid, '01960011-0000-7000-8000-00000000000f'::uuid, 'harold.sanders', 'ACTIVE', 0, NOW()),
    ('01960012-0000-7000-8000-000000000010'::uuid, '01960011-0000-7000-8000-000000000010'::uuid, 'irene.torres', 'ACTIVE', 0, NOW())
ON CONFLICT (link_id) DO NOTHING;

-- employee_location_assignment — unconditional idempotent seed.
-- location_id has no FK, so these are NOT guarded on location existence: a guarded
-- repeatable that ran before pos-location seeded skipped every row and never retried
-- (Flyway repeatables only re-run on checksum change), leaving staff unassigned in
-- every location. Unconditional + ON CONFLICT (id) DO NOTHING is idempotent and
-- ordering-independent, so fresh environments populate regardless of seed order.
INSERT INTO employee_location_assignment (id, employee_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
VALUES
    ('01960013-0000-7000-8000-000000000001'::uuid, '01960014-0000-7000-8000-000000000001'::uuid, '01960003-0000-7000-8000-000000000005'::uuid, 'SYSTEM_ADMINISTRATOR', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000002'::uuid, '01960014-0000-7000-8000-000000000002'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'LOCATION_MANAGER', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000003'::uuid, '01960014-0000-7000-8000-000000000003'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'DISPATCHER', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000004'::uuid, '01960014-0000-7000-8000-000000000004'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'DISPATCHER', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000005'::uuid, '01960014-0000-7000-8000-000000000005'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000006'::uuid, '01960014-0000-7000-8000-000000000006'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000007'::uuid, '01960014-0000-7000-8000-000000000007'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000008'::uuid, '01960014-0000-7000-8000-000000000008'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000009'::uuid, '01960014-0000-7000-8000-000000000009'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-00000000000a'::uuid, '01960014-0000-7000-8000-00000000000a'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-00000000000b'::uuid, '01960014-0000-7000-8000-00000000000b'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-00000000000c'::uuid, '01960014-0000-7000-8000-00000000000c'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'SERVICE_ADVISOR', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-00000000000d'::uuid, '01960014-0000-7000-8000-00000000000d'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'SERVICE_ADVISOR', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-00000000000e'::uuid, '01960014-0000-7000-8000-00000000000e'::uuid, '01960003-0000-7000-8000-000000000005'::uuid, 'ACCOUNTING_ASSOCIATE', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-00000000000f'::uuid, '01960014-0000-7000-8000-00000000000f'::uuid, '01960003-0000-7000-8000-000000000005'::uuid, 'ACCOUNTING_ASSOCIATE', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'),
    ('01960013-0000-7000-8000-000000000010'::uuid, '01960014-0000-7000-8000-000000000010'::uuid, '01960003-0000-7000-8000-000000000005'::uuid, 'ACCOUNT_MANAGER', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator')
ON CONFLICT (id) DO NOTHING;

-- =========================================================================
-- Person contact points (mirrors pos-customer contact_point for the 20
-- commercial primary contacts, 01960025-*). pos-people is SoT for contacts.
--   01960030-*: email points   01960031-*: phone points
-- =========================================================================


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


-- Re-seed emails as EMAIL contact points (person email columns removed).

-- employee_location_assignment for the additional staff (guarded by location existence).
-- effective_from = CURRENT_DATE keeps each row distinct under the
-- (person_id, location_id, role, effective_from) unique key.
DO $$
BEGIN
    IF to_regclass('public.location') IS NOT NULL
    THEN
        INSERT INTO employee_location_assignment (id, employee_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
        SELECT v.id, v.employee_id, v.location_id, v.role, TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
        FROM (
            VALUES
                -- CLT-MAIN-001 technicians
                ('01960013-0000-7000-8000-000000000011'::uuid, '01960014-0000-7000-8000-000000000011'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000012'::uuid, '01960014-0000-7000-8000-000000000012'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000013'::uuid, '01960014-0000-7000-8000-000000000013'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000014'::uuid, '01960014-0000-7000-8000-000000000014'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000015'::uuid, '01960014-0000-7000-8000-000000000015'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN'),
                -- CLT-SOUTH-001 technicians
                ('01960013-0000-7000-8000-000000000016'::uuid, '01960014-0000-7000-8000-000000000016'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000017'::uuid, '01960014-0000-7000-8000-000000000017'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000018'::uuid, '01960014-0000-7000-8000-000000000018'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000019'::uuid, '01960014-0000-7000-8000-000000000019'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-00000000001a'::uuid, '01960014-0000-7000-8000-00000000001a'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN'),
                -- CLT-NORTH-001 technicians
                ('01960013-0000-7000-8000-00000000001b'::uuid, '01960014-0000-7000-8000-00000000001b'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-00000000001c'::uuid, '01960014-0000-7000-8000-00000000001c'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-00000000001d'::uuid, '01960014-0000-7000-8000-00000000001d'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-00000000001e'::uuid, '01960014-0000-7000-8000-00000000001e'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'TECHNICIAN'),
                -- CLT-MOB-HUB-001 technicians
                ('01960013-0000-7000-8000-00000000001f'::uuid, '01960014-0000-7000-8000-00000000001f'::uuid, '01960003-0000-7000-8000-000000000004'::uuid, 'TECHNICIAN'),
                ('01960013-0000-7000-8000-000000000020'::uuid, '01960014-0000-7000-8000-000000000020'::uuid, '01960003-0000-7000-8000-000000000004'::uuid, 'TECHNICIAN'),
                -- LOCATION_MANAGER coverage
                ('01960013-0000-7000-8000-000000000021'::uuid, '01960014-0000-7000-8000-000000000021'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'LOCATION_MANAGER'),
                ('01960013-0000-7000-8000-000000000022'::uuid, '01960014-0000-7000-8000-000000000022'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'LOCATION_MANAGER'),
                ('01960013-0000-7000-8000-000000000023'::uuid, '01960014-0000-7000-8000-000000000023'::uuid, '01960003-0000-7000-8000-000000000004'::uuid, 'LOCATION_MANAGER'),
                -- DISPATCHER coverage
                ('01960013-0000-7000-8000-000000000024'::uuid, '01960014-0000-7000-8000-000000000024'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'DISPATCHER'),
                ('01960013-0000-7000-8000-000000000025'::uuid, '01960014-0000-7000-8000-000000000025'::uuid, '01960003-0000-7000-8000-000000000004'::uuid, 'DISPATCHER'),
                -- SERVICE_ADVISOR coverage
                ('01960013-0000-7000-8000-000000000026'::uuid, '01960014-0000-7000-8000-000000000026'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'SERVICE_ADVISOR'),
                ('01960013-0000-7000-8000-000000000027'::uuid, '01960014-0000-7000-8000-000000000027'::uuid, '01960003-0000-7000-8000-000000000004'::uuid, 'SERVICE_ADVISOR')
        ) AS v(id, employee_id, location_id, role)
        WHERE EXISTS (SELECT 1 FROM public.location l WHERE l.id = v.location_id)
        ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;
