-- Repeatable seed migration for pos-people operational data.
-- 16 employees across 7 roles for Durion Positivity (medium truck mechanical repair corporation).
-- Locations: CLT-MAIN-001, CLT-SOUTH-001, CLT-NORTH-001, CORP-HQ-001
--
-- Additional person rows (non-employees) for cross-service FK alignment:
--   01960024-*: 50 customer persons (person_party.person_id in pos-customer)
--   01960025-*: 20 commercial primary contacts (contact.person_id in pos-customer)
--   01960026-*: 20 commercial billing contacts (contact.person_id in pos-customer)
SET TIME ZONE 'UTC';

-- person rows
INSERT INTO person (id, first_name, last_name, username, employee_number, primary_email, status, hire_date, created_at, updated_at)
VALUES
    ('01960011-0000-7000-8000-000000000001'::uuid, 'Marcus',   'Webb',     'marcus.webb',     'EMP-0001', 'marcus.webb@durion.internal',     'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000002'::uuid, 'Diana',    'Rowe',     'diana.rowe',      'EMP-0002', 'diana.rowe@durion.internal',      'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000003'::uuid, 'Terrence', 'Blake',    'terrence.blake',  'EMP-0003', 'terrence.blake@durion.internal',  'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000004'::uuid, 'Sandra',   'Cruz',     'sandra.cruz',     'EMP-0004', 'sandra.cruz@durion.internal',     'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000005'::uuid, 'Kyle',     'Brennan',  'kyle.brennan',    'EMP-0005', 'kyle.brennan@durion.internal',    'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000006'::uuid, 'DeShawn',  'Morris',   'deshawn.morris',  'EMP-0006', 'deshawn.morris@durion.internal',  'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000007'::uuid, 'Carlos',   'Ruiz',     'carlos.ruiz',     'EMP-0007', 'carlos.ruiz@durion.internal',     'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000008'::uuid, 'Amber',    'Nguyen',   'amber.nguyen',    'EMP-0008', 'amber.nguyen@durion.internal',    'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000009'::uuid, 'Eddie',    'Vasquez',  'eddie.vasquez',   'EMP-0009', 'eddie.vasquez@durion.internal',   'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000a'::uuid, 'Priya',    'Patel',    'priya.patel',     'EMP-0010', 'priya.patel@durion.internal',     'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000b'::uuid, 'James',    'Okafor',   'james.okafor',    'EMP-0011', 'james.okafor@durion.internal',    'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000c'::uuid, 'Rachel',   'Kim',      'rachel.kim',      'EMP-0012', 'rachel.kim@durion.internal',      'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000d'::uuid, 'Tyrone',   'Williams', 'tyrone.williams', 'EMP-0013', 'tyrone.williams@durion.internal', 'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000e'::uuid, 'Olivia',   'Chen',     'olivia.chen',     'EMP-0014', 'olivia.chen@durion.internal',     'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-00000000000f'::uuid, 'Harold',   'Sanders',  'harold.sanders',  'EMP-0015', 'harold.sanders@durion.internal',  'ACTIVE', CURRENT_DATE, NOW(), NOW()),
    ('01960011-0000-7000-8000-000000000010'::uuid, 'Irene',    'Torres',   'irene.torres',    'EMP-0016', 'irene.torres@durion.internal',    'ACTIVE', CURRENT_DATE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- =========================================================================
-- Group A: 50 customer persons (01960024-*) — person_party.person_id in pos-customer
-- =========================================================================

INSERT INTO person (id, first_name, last_name, primary_email, status, created_at, updated_at)
VALUES
    ('01960024-0000-7000-8000-000000000001'::uuid, 'Marcus',   'Patterson', 'marcus.patterson@example.com',   'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000002'::uuid, 'Jennifer', 'Holloway',  'jennifer.holloway@example.com',  'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000003'::uuid, 'Robert',   'Castillo',  'robert.castillo@example.com',    'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000004'::uuid, 'Angela',   'Freeman',   'angela.freeman@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000005'::uuid, 'Derek',    'Washington','derek.washington@example.com',   'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000006'::uuid, 'Patricia', 'Simmons',   'patricia.simmons@example.com',   'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000007'::uuid, 'Kevin',    'Thornton',  'kevin.thornton@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000008'::uuid, 'Linda',    'Guerrero',  'linda.guerrero@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000009'::uuid, 'James',    'Caldwell',  'james.caldwell@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000a'::uuid, 'Tanya',    'Robinson',  'tanya.robinson@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000b'::uuid, 'Michael',  'Owens',     'michael.owens@example.com',      'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000c'::uuid, 'Cheryl',   'Hawkins',   'cheryl.hawkins@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000d'::uuid, 'Ronald',   'Jenkins',   'ronald.jenkins@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000e'::uuid, 'Denise',   'Foster',    'denise.foster@example.com',      'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000000f'::uuid, 'Anthony',  'Bryant',    'anthony.bryant@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000010'::uuid, 'Brenda',   'Coleman',   'brenda.coleman@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000011'::uuid, 'Steven',   'Gardner',   'steven.gardner@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000012'::uuid, 'Nicole',   'Harrison',  'nicole.harrison@example.com',    'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000013'::uuid, 'Gary',     'Alexander', 'gary.alexander@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000014'::uuid, 'Carolyn',  'Mitchell',  'carolyn.mitchell@example.com',   'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000015'::uuid, 'Timothy',  'Dixon',     'timothy.dixon@example.com',      'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000016'::uuid, 'Sandra',   'Reeves',    'sandra.reeves@example.com',      'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000017'::uuid, 'Walter',   'Hughes',    'walter.hughes@example.com',      'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000018'::uuid, 'Pamela',   'Lewis',     'pamela.lewis@example.com',       'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000019'::uuid, 'Larry',    'Peterson',  'larry.peterson@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001a'::uuid, 'Deborah',  'Barnes',    'deborah.barnes@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001b'::uuid, 'Frank',    'Murphy',    'frank.murphy@example.com',       'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001c'::uuid, 'Sharon',   'Powell',    'sharon.powell@example.com',      'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001d'::uuid, 'Raymond',  'Bailey',    'raymond.bailey@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001e'::uuid, 'Cynthia',  'Ross',      'cynthia.ross@example.com',       'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000001f'::uuid, 'Jose',     'Rivera',    'jose.rivera@example.com',        'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000020'::uuid, 'Gloria',   'Turner',    'gloria.turner@example.com',      'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000021'::uuid, 'Douglas',  'Stewart',   'douglas.stewart@example.com',    'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000022'::uuid, 'Shirley',  'Flores',    'shirley.flores@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000023'::uuid, 'Henry',    'Griffin',   'henry.griffin@example.com',      'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000024'::uuid, 'Marie',    'Evans',     'marie.evans@example.com',        'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000025'::uuid, 'Bruce',    'King',      'bruce.king@example.com',         'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000026'::uuid, 'Wanda',    'Sanchez',   'wanda.sanchez@example.com',      'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000027'::uuid, 'Keith',    'Ward',      'keith.ward@example.com',         'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000028'::uuid, 'Phyllis',  'Long',      'phyllis.long@example.com',       'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000029'::uuid, 'Carl',     'Price',     'carl.price@example.com',         'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002a'::uuid, 'Martha',   'Scott',     'martha.scott@example.com',       'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002b'::uuid, 'Albert',   'Rogers',    'albert.rogers@example.com',      'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002c'::uuid, 'Virginia', 'Henderson', 'virginia.henderson@example.com', 'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002d'::uuid, 'Harry',    'Hill',      'harry.hill@example.com',         'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002e'::uuid, 'Doris',    'Wood',      'doris.wood@example.com',         'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-00000000002f'::uuid, 'Raymond',  'James',     'raymond.james@example.com',      'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000030'::uuid, 'Betty',    'Crawford',  'betty.crawford@example.com',     'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000031'::uuid, 'Samuel',   'Reed',      'samuel.reed@example.com',        'ACTIVE', NOW(), NOW()),
    ('01960024-0000-7000-8000-000000000032'::uuid, 'Dorothy',  'Bell',      'dorothy.bell@example.com',       'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- =========================================================================
-- Group B: 20 commercial primary contact persons (01960025-*) — contact.person_id in pos-customer
-- =========================================================================

INSERT INTO person (id, first_name, last_name, primary_email, status, created_at, updated_at)
VALUES
    ('01960025-0000-7000-8000-000000000001'::uuid, 'Greg',      'Whitfield',  'g.whitfield@piedmontfreight.example.com',   'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000002'::uuid, 'Teresa',    'Mullen',     't.mullen@carolinaconcrete.example.com',     'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000003'::uuid, 'Darnell',   'Okafor',     'd.okafor@qcwaste.example.com',              'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000004'::uuid, 'Brittany',  'Norris',     'b.norris@blueridgelandscaping.example.com', 'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000005'::uuid, 'Marcus',    'Tillman',    'm.tillman@tarheellogistics.example.com',    'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000006'::uuid, 'Christine', 'Walters',    'c.walters@meckplumbing.example.com',        'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000007'::uuid, 'Donald',    'Frazier',    'd.frazier@piedmontreadymix.example.com',    'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000008'::uuid, 'Alicia',    'Stephens',   'a.stephens@carolinapower.example.com',      'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000009'::uuid, 'Keith',     'Burnham',    'k.burnham@bluestoneaggregate.example.com',  'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000a'::uuid, 'Tamara',    'McPherson',  't.mcpherson@cabarruscleaning.example.com',  'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000b'::uuid, 'Wesley',    'Parrish',    'w.parrish@sedelivery.example.com',          'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000c'::uuid, 'Renee',     'Holt',       'r.holt@carolinascrane.example.com',         'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000d'::uuid, 'Calvin',    'Dunmore',    'c.dunmore@lknpropane.example.com',          'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000e'::uuid, 'Latasha',   'Gooden',     'l.gooden@rowanroad.example.com',            'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-00000000000f'::uuid, 'Bryan',     'Cantrell',   'b.cantrell@carolinafresh.example.com',      'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000010'::uuid, 'Monica',    'Byrd',       'm.byrd@piedmontmetals.example.com',         'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000011'::uuid, 'Cedric',    'Blackwell',  'c.blackwell@uniongrading.example.com',      'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000012'::uuid, 'Veronica',  'Pratt',      'v.pratt@carolinaseptic.example.com',        'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000013'::uuid, 'Jonathon',  'Culpepper',  'j.culpepper@mecktree.example.com',          'ACTIVE', NOW(), NOW()),
    ('01960025-0000-7000-8000-000000000014'::uuid, 'Sheryl',    'Davenport',  's.davenport@highlandmoving.example.com',    'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- =========================================================================
-- Group C: 20 commercial billing contact persons (01960026-*) — contact.person_id in pos-customer
--          Represents the org-level billing/admin inbox preserved from commercial_party.email.
-- =========================================================================

-- Commercial-account contact persons. These are real individuals, not employees,
-- so status is NULL per the People Directory contract (null = non-employee person).
-- Names mirror the matching CUST-CPC contacts in pos-customer person_party.
INSERT INTO person (id, first_name, last_name, primary_email, status, created_at, updated_at)
VALUES
    ('01960026-0000-7000-8000-000000000001'::uuid, 'Greg',      'Whitfield',   'info@piedmontfreight.example.com',           NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-000000000002'::uuid, 'Teresa',    'Mullen',      'accounts@carolinaconcrete.example.com',      NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-000000000003'::uuid, 'Darnell',   'Okafor',      'billing@qcwaste.example.com',                NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-000000000004'::uuid, 'Brittany',  'Norris',      'fleet@blueridgelandscaping.example.com',     NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-000000000005'::uuid, 'Marcus',    'Tillman',     'ops@tarheellogistics.example.com',           NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-000000000006'::uuid, 'Christine', 'Walters',     'dispatch@meckplumbing.example.com',          NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-000000000007'::uuid, 'Donald',    'Frazier',     'fleet@piedmontreadymix.example.com',         NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-000000000008'::uuid, 'Alicia',    'Stephens',    'service@carolinapower.example.com',          NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-000000000009'::uuid, 'Keith',     'Burnham',     'accounts@bluestoneaggregate.example.com',    NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-00000000000a'::uuid, 'Tamara',    'McPherson',   'billing@cabarruscleaning.example.com',       NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-00000000000b'::uuid, 'Wesley',    'Parrish',     'ops@sedelivery.example.com',                 NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-00000000000c'::uuid, 'Renee',     'Holt',        'fleet@carolinascrane.example.com',           NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-00000000000d'::uuid, 'Calvin',    'Dunmore',     'service@lknpropane.example.com',             NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-00000000000e'::uuid, 'Latasha',   'Gooden',      'accounts@rowanroad.example.com',             NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-00000000000f'::uuid, 'Bryan',     'Cantrell',    'dispatch@carolinafresh.example.com',         NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-000000000010'::uuid, 'Monica',    'Byrd',        'billing@piedmontmetals.example.com',         NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-000000000011'::uuid, 'Cedric',    'Blackwell',   'ops@uniongrading.example.com',               NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-000000000012'::uuid, 'Veronica',  'Pratt',       'service@carolinaseptic.example.com',         NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-000000000013'::uuid, 'Jonathon',  'Culpepper',   'fleet@mecktree.example.com',                 NULL, NOW(), NOW()),
    ('01960026-0000-7000-8000-000000000014'::uuid, 'Sheryl',    'Davenport',   'billing@highlandmoving.example.com',         NULL, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

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

-- person_location_assignment (guarded by person and location table existence)
-- Each row skipped if its target location does not yet exist (cross-module dependency).
DO $$
BEGIN
    IF to_regclass('public.person') IS NOT NULL
       AND to_regclass('public.location') IS NOT NULL
    THEN
        IF EXISTS (
            SELECT 1
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
              AND c.relname = 'location'
        ) THEN
            -- marcus.webb → CORP-HQ-001 (SYSTEM_ADMINISTRATOR)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-000000000001'::uuid, '01960011-0000-7000-8000-000000000001'::uuid, '01960003-0000-7000-8000-000000000005'::uuid, 'SYSTEM_ADMINISTRATOR', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000005'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;

            -- diana.rowe → CLT-MAIN-001 (LOCATION_MANAGER)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-000000000002'::uuid, '01960011-0000-7000-8000-000000000002'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'LOCATION_MANAGER', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000001'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;

            -- terrence.blake → CLT-MAIN-001 (DISPATCHER)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-000000000003'::uuid, '01960011-0000-7000-8000-000000000003'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'DISPATCHER', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000001'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;

            -- sandra.cruz → CLT-SOUTH-001 (DISPATCHER)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-000000000004'::uuid, '01960011-0000-7000-8000-000000000004'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'DISPATCHER', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000002'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;

            -- kyle.brennan → CLT-MAIN-001 (TECHNICIAN)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-000000000005'::uuid, '01960011-0000-7000-8000-000000000005'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000001'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;

            -- deshawn.morris → CLT-MAIN-001 (TECHNICIAN)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-000000000006'::uuid, '01960011-0000-7000-8000-000000000006'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000001'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;

            -- carlos.ruiz → CLT-MAIN-001 (TECHNICIAN)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-000000000007'::uuid, '01960011-0000-7000-8000-000000000007'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000001'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;

            -- amber.nguyen → CLT-SOUTH-001 (TECHNICIAN)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-000000000008'::uuid, '01960011-0000-7000-8000-000000000008'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000002'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;

            -- eddie.vasquez → CLT-SOUTH-001 (TECHNICIAN)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-000000000009'::uuid, '01960011-0000-7000-8000-000000000009'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000002'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;

            -- priya.patel → CLT-NORTH-001 (TECHNICIAN)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-00000000000a'::uuid, '01960011-0000-7000-8000-00000000000a'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000003'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;

            -- james.okafor → CLT-NORTH-001 (TECHNICIAN)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-00000000000b'::uuid, '01960011-0000-7000-8000-00000000000b'::uuid, '01960003-0000-7000-8000-000000000003'::uuid, 'TECHNICIAN', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000003'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;

            -- rachel.kim → CLT-MAIN-001 (SERVICE_ADVISOR)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-00000000000c'::uuid, '01960011-0000-7000-8000-00000000000c'::uuid, '01960003-0000-7000-8000-000000000001'::uuid, 'SERVICE_ADVISOR', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000001'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;

            -- tyrone.williams → CLT-SOUTH-001 (SERVICE_ADVISOR)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-00000000000d'::uuid, '01960011-0000-7000-8000-00000000000d'::uuid, '01960003-0000-7000-8000-000000000002'::uuid, 'SERVICE_ADVISOR', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000002'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;

            -- olivia.chen → CORP-HQ-001 (ACCOUNTING_ASSOCIATE)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-00000000000e'::uuid, '01960011-0000-7000-8000-00000000000e'::uuid, '01960003-0000-7000-8000-000000000005'::uuid, 'ACCOUNTING_ASSOCIATE', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000005'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;

            -- harold.sanders → CORP-HQ-001 (ACCOUNTING_ASSOCIATE)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-00000000000f'::uuid, '01960011-0000-7000-8000-00000000000f'::uuid, '01960003-0000-7000-8000-000000000005'::uuid, 'ACCOUNTING_ASSOCIATE', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000005'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;

            -- irene.torres → CORP-HQ-001 (ACCOUNT_MANAGER)
            EXECUTE $sql$
                INSERT INTO person_location_assignment (id, person_id, location_id, role, is_primary, status, effective_from, created_at, updated_at, created_by)
                SELECT '01960013-0000-7000-8000-000000000010'::uuid, '01960011-0000-7000-8000-000000000010'::uuid, '01960003-0000-7000-8000-000000000005'::uuid, 'ACCOUNT_MANAGER', TRUE, 'ACTIVE', CURRENT_DATE, NOW(), NOW(), 'seed-generator'
                WHERE EXISTS (SELECT 1 FROM public.location WHERE id = '01960003-0000-7000-8000-000000000005'::uuid)
                ON CONFLICT (id) DO NOTHING
            $sql$;
        END IF;
    END IF;
END $$;

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
    ('01960031-0000-7000-8000-000000000014'::uuid, '01960025-0000-7000-8000-000000000014'::uuid, 'PHONE_WORK', '980-555-3020', true, NOW(), NOW()),
ON CONFLICT (id) DO NOTHING;
