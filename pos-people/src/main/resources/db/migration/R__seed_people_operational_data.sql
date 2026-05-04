-- Repeatable seed migration for pos-people operational data.
-- 16 employees across 7 roles for Durion Positivity (medium truck mechanical repair corporation).
-- Locations: CLT-MAIN-001, CLT-SOUTH-001, CLT-NORTH-001, CORP-HQ-001
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
