-- Repeatable seed migration for people reference/bootstrap data.
-- Notes:
-- - HR tables (employee, assignments, timekeeping_policy) plus dev-bootstrap rows for the
--   ext_people_contact_* replicas (identity is owned by pos-people-contact since #874/#875).
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

-- ext_people_contact_person replica bootstrap for admin.alpha (identity owned by
-- pos-people-contact since #874; dev/docker convenience seed, ids match the authority's seed).
INSERT INTO ext_people_contact_person (person_id, first_name, last_name, primary_email, aggregate_version, updated_at)
VALUES ('583fa3b3-d1bf-a40d-8e21-8cd54424d5d0'::uuid, 'System', 'Administrator', 'admin.alpha@durionpos.org', 0, NOW())
ON CONFLICT (person_id) DO NOTHING;

-- employee (employment record for admin.alpha; ACTIVE).
INSERT INTO employee (id, person_id, employee_number, status, hire_date, status_effective_at, created_at, updated_at)
VALUES (
    'a0000001-0000-7000-8000-000000000001'::uuid,
    '583fa3b3-d1bf-a40d-8e21-8cd54424d5d0'::uuid,
    NULL, 'ACTIVE', NULL, NOW(), NOW(), NOW()
)
ON CONFLICT (person_id) DO NOTHING;

-- ext_people_contact_user_link replica bootstrap for admin.alpha.
INSERT INTO ext_people_contact_user_link (link_id, person_id, username, status, aggregate_version, updated_at)
VALUES ('4790360f-65ab-20e9-88e3-7bf9277bf2b9'::uuid, '583fa3b3-d1bf-a40d-8e21-8cd54424d5d0'::uuid, 'admin.alpha', 'ACTIVE', 0, NOW())
ON CONFLICT (link_id) DO NOTHING;

-- employee_location_assignment (guarded by external location + employee existence).
DO $$
BEGIN
    IF to_regclass('public.location') IS NOT NULL
       AND EXISTS (SELECT 1 FROM employee WHERE id = 'a0000001-0000-7000-8000-000000000001'::uuid)
    THEN
        EXECUTE $sql$
            INSERT INTO employee_location_assignment (
                id,
                employee_id,
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
                'a0000001-0000-7000-8000-000000000001'::uuid,
                'f3ad439a-7dff-850c-395e-ea280bb82f05'::uuid,
                'MANAGER',
                TRUE,
                'ACTIVE',
                CURRENT_DATE,
                NOW(),
                NOW(),
                'seed-generator'
            WHERE EXISTS (
                SELECT 1 FROM public.location
                WHERE id = 'f3ad439a-7dff-850c-395e-ea280bb82f05'::uuid
            )
            ON CONFLICT (id) DO NOTHING
        $sql$;
    END IF;
END $$;
