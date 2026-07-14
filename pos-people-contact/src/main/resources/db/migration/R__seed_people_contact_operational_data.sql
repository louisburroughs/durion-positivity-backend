-- Repeatable seed migration for pos-people-contact operational identity data (ADR-0044 §6, #875).
-- Ported from pos-people's operational seed when identity ownership moved (#874):
-- 39 employee persons (01960011-*), 50 customer persons (01960024-*), 20+20 commercial
-- contact persons (01960025-*/01960026-*) for cross-service id alignment, their contact
-- points, and the seeded user-person links.
SET TIME ZONE 'UTC';

INSERT INTO person (id, first_name, last_name, created_at, updated_at)
VALUES
    ('01960011-0000-7000-8000-000000000001'::uuid, 'Marcus', 'Webb', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000002'::uuid, 'Diana', 'Rowe', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000003'::uuid, 'Terrence', 'Blake', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000004'::uuid, 'Sandra', 'Cruz', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000005'::uuid, 'Kyle', 'Brennan', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000006'::uuid, 'DeShawn', 'Morris', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000007'::uuid, 'Carlos', 'Ruiz', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000008'::uuid, 'Amber', 'Nguyen', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000009'::uuid, 'Eddie', 'Vasquez', NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000a'::uuid, 'Priya', 'Patel', NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000b'::uuid, 'James', 'Okafor', NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000c'::uuid, 'Rachel', 'Kim', NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000d'::uuid, 'Tyrone', 'Williams', NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000e'::uuid, 'Olivia', 'Chen', NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000f'::uuid, 'Harold', 'Sanders', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000010'::uuid, 'Irene', 'Torres', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000011'::uuid, 'Hector', 'Alvarez', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000012'::uuid, 'Naomi', 'Ford', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000013'::uuid, 'Trevor', 'Quinn', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000014'::uuid, 'Camille', 'Boyd', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000015'::uuid, 'Andre', 'Foster', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000016'::uuid, 'Lila', 'Montgomery', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000017'::uuid, 'Desmond', 'Pace', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000018'::uuid, 'Brooke', 'Hadley', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000019'::uuid, 'Felix', 'Romano', NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000001a'::uuid, 'Gina', 'Vaughn', NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000001b'::uuid, 'Omar', 'Haddad', NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000001c'::uuid, 'Sierra', 'Lowe', NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000001d'::uuid, 'Russell', 'Pike', NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000001e'::uuid, 'Maya', 'Devlin', NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000001f'::uuid, 'Caleb', 'Frost', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000020'::uuid, 'Yvonne', 'Marsh', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000021'::uuid, 'Bernard', 'Cole', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000022'::uuid, 'Gloria', 'Mensah', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000023'::uuid, 'Victor', 'Salazar', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000024'::uuid, 'Renee', 'Albright', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000025'::uuid, 'Curtis', 'Benton', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000026'::uuid, 'Paula', 'Knight', NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000027'::uuid, 'Simon', 'Hayes', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
INSERT INTO person (id, first_name, last_name, created_at, updated_at)
VALUES
    ('01960024-0000-7000-8000-000000000001'::uuid, 'Marcus', 'Patterson', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000002'::uuid, 'Jennifer', 'Holloway', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000003'::uuid, 'Robert', 'Castillo', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000004'::uuid, 'Angela', 'Freeman', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000005'::uuid, 'Derek', 'Washington', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000006'::uuid, 'Patricia', 'Simmons', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000007'::uuid, 'Kevin', 'Thornton', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000008'::uuid, 'Linda', 'Guerrero', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000009'::uuid, 'James', 'Caldwell', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000a'::uuid, 'Tanya', 'Robinson', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000b'::uuid, 'Michael', 'Owens', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000c'::uuid, 'Cheryl', 'Hawkins', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000d'::uuid, 'Ronald', 'Jenkins', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000e'::uuid, 'Denise', 'Foster', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000f'::uuid, 'Anthony', 'Bryant', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000010'::uuid, 'Brenda', 'Coleman', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000011'::uuid, 'Steven', 'Gardner', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000012'::uuid, 'Nicole', 'Harrison', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000013'::uuid, 'Gary', 'Alexander', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000014'::uuid, 'Carolyn', 'Mitchell', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000015'::uuid, 'Timothy', 'Dixon', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000016'::uuid, 'Sandra', 'Reeves', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000017'::uuid, 'Walter', 'Hughes', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000018'::uuid, 'Pamela', 'Lewis', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000019'::uuid, 'Larry', 'Peterson', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001a'::uuid, 'Deborah', 'Barnes', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001b'::uuid, 'Frank', 'Murphy', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001c'::uuid, 'Sharon', 'Powell', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001d'::uuid, 'Raymond', 'Bailey', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001e'::uuid, 'Cynthia', 'Ross', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001f'::uuid, 'Jose', 'Rivera', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000020'::uuid, 'Gloria', 'Turner', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000021'::uuid, 'Douglas', 'Stewart', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000022'::uuid, 'Shirley', 'Flores', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000023'::uuid, 'Henry', 'Griffin', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000024'::uuid, 'Marie', 'Evans', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000025'::uuid, 'Bruce', 'King', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000026'::uuid, 'Wanda', 'Sanchez', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000027'::uuid, 'Keith', 'Ward', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000028'::uuid, 'Phyllis', 'Long', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000029'::uuid, 'Carl', 'Price', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002a'::uuid, 'Martha', 'Scott', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002b'::uuid, 'Albert', 'Rogers', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002c'::uuid, 'Virginia', 'Henderson', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002d'::uuid, 'Harry', 'Hill', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002e'::uuid, 'Doris', 'Wood', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002f'::uuid, 'Raymond', 'James', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000030'::uuid, 'Betty', 'Crawford', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000031'::uuid, 'Samuel', 'Reed', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000032'::uuid, 'Dorothy', 'Bell', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
INSERT INTO person (id, first_name, last_name, created_at, updated_at)
VALUES
    ('01960025-0000-7000-8000-000000000001'::uuid, 'Greg', 'Whitfield', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000002'::uuid, 'Teresa', 'Mullen', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000003'::uuid, 'Darnell', 'Okafor', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000004'::uuid, 'Brittany', 'Norris', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000005'::uuid, 'Marcus', 'Tillman', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000006'::uuid, 'Christine', 'Walters', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000007'::uuid, 'Donald', 'Frazier', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000008'::uuid, 'Alicia', 'Stephens', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000009'::uuid, 'Keith', 'Burnham', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000a'::uuid, 'Tamara', 'McPherson', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000b'::uuid, 'Wesley', 'Parrish', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000c'::uuid, 'Renee', 'Holt', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000d'::uuid, 'Calvin', 'Dunmore', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000e'::uuid, 'Latasha', 'Gooden', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000f'::uuid, 'Bryan', 'Cantrell', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000010'::uuid, 'Monica', 'Byrd', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000011'::uuid, 'Cedric', 'Blackwell', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000012'::uuid, 'Veronica', 'Pratt', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000013'::uuid, 'Jonathon', 'Culpepper', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000014'::uuid, 'Sheryl', 'Davenport', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Typed contact points (EMAIL primary / PHONE_WORK).
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

-- user_person_links (keyed by username; UNIQUE(username) guards duplicate seeds).
INSERT INTO user_person_links (id, username, person_id, link_type, status, created_at, created_by)
        VALUES
            ('01960012-0000-7000-8000-000000000001'::uuid, 'marcus.webb', '01960011-0000-7000-8000-000000000001'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000002'::uuid, 'diana.rowe', '01960011-0000-7000-8000-000000000002'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000003'::uuid, 'terrence.blake', '01960011-0000-7000-8000-000000000003'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000004'::uuid, 'sandra.cruz', '01960011-0000-7000-8000-000000000004'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000005'::uuid, 'kyle.brennan', '01960011-0000-7000-8000-000000000005'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000006'::uuid, 'deshawn.morris', '01960011-0000-7000-8000-000000000006'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000007'::uuid, 'carlos.ruiz', '01960011-0000-7000-8000-000000000007'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000008'::uuid, 'amber.nguyen', '01960011-0000-7000-8000-000000000008'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000009'::uuid, 'eddie.vasquez', '01960011-0000-7000-8000-000000000009'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-00000000000a'::uuid, 'priya.patel', '01960011-0000-7000-8000-00000000000a'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-00000000000b'::uuid, 'james.okafor', '01960011-0000-7000-8000-00000000000b'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-00000000000c'::uuid, 'rachel.kim', '01960011-0000-7000-8000-00000000000c'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-00000000000d'::uuid, 'tyrone.williams', '01960011-0000-7000-8000-00000000000d'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-00000000000e'::uuid, 'olivia.chen', '01960011-0000-7000-8000-00000000000e'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-00000000000f'::uuid, 'harold.sanders', '01960011-0000-7000-8000-00000000000f'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator'),
            ('01960012-0000-7000-8000-000000000010'::uuid, 'irene.torres', '01960011-0000-7000-8000-000000000010'::uuid, 'PRIMARY', 'ACTIVE', NOW(), 'seed-generator')
        ON CONFLICT (username) DO NOTHING;
