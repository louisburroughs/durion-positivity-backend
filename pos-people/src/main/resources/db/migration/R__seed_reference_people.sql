-- Repeatable seed migration for people reference/bootstrap data.
-- Source: durion/scripts/seed-generator/generated-seed-sql/003_locations_people.sql
-- Notes:
-- - Includes only pos-people-owned tables.
-- - user_person_links and person_location_assignment rows are inserted only when referenced external rows exist.
SET TIME ZONE 'UTC';

-- timekeeping_policy
INSERT INTO timekeeping_policy (
    timekeeping_policy_id,
    scope_type,
    scope_id,
    job_time_discrepancy_threshold_minutes,
    effective_start_at,
    updated_by,
    created_at,
    updated_at
)
VALUES ('7b1f81a7-34fa-f0f9-7caf-a55541d36a60'::uuid, 'GLOBAL', NULL, 10, NOW(), 'seed-generator', NOW(), NOW())
ON CONFLICT (timekeeping_policy_id) DO NOTHING;

-- person (System Administrator) — admin.alpha's person record. Required so the
-- guarded user_person_links insert below fires; without it admin.alpha is a User
-- with no Person, violating ADR-0015 §3 (durion-positivity-backend#714).
INSERT INTO person (id, first_name, last_name, legal_name, status, status_effective_at, created_at, updated_at)
VALUES (
    '583fa3b3-d1bf-a40d-8e21-8cd54424d5d0'::uuid,
    'System', 'Administrator', 'System Administrator',
    'ACTIVE', NOW(), NOW(), NOW()
)
ON CONFLICT (id) DO NOTHING;

-- Email now lives in person_contact_point (EMAIL); username is resolved via
-- user_person_links → pos-security. Re-seed admin.alpha's email here.
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '583fa3b3-d1bf-a40d-8e21-8cd54424d5d0'::uuid, 'EMAIL', 'admin.alpha@durionpos.org', TRUE, NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM person_contact_point
    WHERE person_id = '583fa3b3-d1bf-a40d-8e21-8cd54424d5d0'::uuid
      AND contact_type = 'EMAIL'
      AND value = 'admin.alpha@durionpos.org');

-- user_person_links (guarded by external person existence)
DO $$
BEGIN
    IF to_regclass('public.person') IS NOT NULL
       AND EXISTS (SELECT 1 FROM person WHERE id = '583fa3b3-d1bf-a40d-8e21-8cd54424d5d0'::uuid)
    THEN
        -- ON CONFLICT (user_id): user_person_links has a UNIQUE(user_id) constraint, so
        -- guard on user_id (not just the PK id) — a link for this user under a different
        -- id from an earlier seed must not raise a unique violation here.
        INSERT INTO user_person_links (id, user_id, person_id, link_type, status, created_at, created_by)
        VALUES (
            '4790360f-65ab-20e9-88e3-7bf9277bf2b9'::uuid,
            'd981cd20-55a1-b43c-9332-0ef2cd630e1a',
            '583fa3b3-d1bf-a40d-8e21-8cd54424d5d0'::uuid,
            'PRIMARY',
            'ACTIVE',
            NOW(),
            'seed-generator'
        )
        ON CONFLICT (user_id) DO NOTHING;
    END IF;
END $$;

-- person_location_assignment (guarded by external person/location existence)
DO $$
BEGIN
    IF to_regclass('public.person') IS NOT NULL
       AND to_regclass('public.location') IS NOT NULL
       AND EXISTS (SELECT 1 FROM person WHERE id = '583fa3b3-d1bf-a40d-8e21-8cd54424d5d0'::uuid)
    THEN
        IF EXISTS (
            SELECT 1
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
              AND c.relname = 'location'
        ) THEN
            EXECUTE $sql$
                INSERT INTO person_location_assignment (
                    id,
                    person_id,
                    location_id,
                    role,
                    is_primary,
                    status,
                    effective_from,
                    created_at,
                    updated_at,
                    created_by
                )
                SELECT
                    '39462b92-de5d-5744-79f0-e0ae6dea1940'::uuid,
                    '583fa3b3-d1bf-a40d-8e21-8cd54424d5d0'::uuid,
                    'f3ad439a-7dff-850c-395e-ea280bb82f05'::uuid,
                    'MANAGER',
                    TRUE,
                    'ACTIVE',
                    CURRENT_DATE,
                    NOW(),
                    NOW(),
                    'seed-generator'
                WHERE EXISTS (
                    SELECT 1
                    FROM public.location
                    WHERE id = 'f3ad439a-7dff-850c-395e-ea280bb82f05'::uuid
                )
                ON CONFLICT (id) DO NOTHING
            $sql$;
        END IF;
    END IF;
END $$;
