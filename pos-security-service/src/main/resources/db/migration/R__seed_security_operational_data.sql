-- Tenant binding for the seed rows below (ADR-0062); transaction-local.
SELECT set_config('app.current_tenant', '01900000-0000-7000-8000-000000000001', true);

-- Repeatable seed migration for pos-security-service operational users.
-- 25 users (23 employees + 2 customer personas) across 16 roles for Durion Positivity (medium truck mechanical repair corporation).
-- The 8 users added 2026-08 (…012-…019) fill roles that previously had no seeded user at
-- all, so every persona is exercisable under its own login (audit doc
-- docs/rbac-permission-role-audit-2026-08.md §7 Task 10). walter.simmons (CUSTOMER) and
-- lena.fischer (SELF_SERVICE_CUSTOMER) are plain seeded operational users standing in for
-- customer personas; the real customer flow is expected to go through self-registration /
-- ExtCustomerPersonIdentity rather than this admin-seeded users table — see the audit doc's
-- Task 10 "open mechanic" flag for the undecided call on whether that substitution is
-- representative enough for the integration suite.

SET TIME ZONE 'UTC';

-- Operational users
INSERT INTO users (id, username, password, enabled)
VALUES
    ('01960010-0000-7000-8000-000000000001', 'marcus.webb',     '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000002', 'diana.rowe',      '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000003', 'terrence.blake',  '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000004', 'sandra.cruz',     '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000005', 'kyle.brennan',    '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000006', 'deshawn.morris',  '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000007', 'carlos.ruiz',     '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000008', 'amber.nguyen',    '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000009', 'eddie.vasquez',   '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-00000000000a', 'priya.patel',     '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-00000000000b', 'james.okafor',    '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-00000000000c', 'rachel.kim',      '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-00000000000d', 'tyrone.williams', '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-00000000000e', 'olivia.chen',     '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-00000000000f', 'harold.sanders',  '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000010', 'irene.torres',    '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000011', 'gloria.mendez',   '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000012', 'victor.hale',     '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000013', 'nina.alvarez',    '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000014', 'doug.freeman',    '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000015', 'felicia.grant',   '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000016', 'raymond.chu',     '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000017', 'walter.simmons',  '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000018', 'lena.fischer',    '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true),
    ('01960010-0000-7000-8000-000000000019', 'margaret.olsen',  '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true)
ON CONFLICT (tenant_id, username) DO UPDATE SET password = EXCLUDED.password, enabled = EXCLUDED.enabled;

-- Role assignments (resolved by role name to tolerate variable UUIDs from versioned migrations)
--
-- #1613 D8: these joins now resolve only for roles Flyway still creates. On a fresh database the
-- rest of the roles do not exist yet when this runs, so those rows match nothing and no assignment
-- is made. That is covered rather than broken: scripts/fixtures/seed/alpha/security/users.csv
-- provisions the same 25 accounts with the same roles through the SECURITY_USER loader, which runs
-- after the role load. On an environment that already has the roles this seed applies as before.
--
-- ADR-0061 amendment (2026-09-09, #1914) phase 2: role_assignments is the only store of a user's
-- roles now, so this grants an open-ended assignment directly rather than a user_roles row (which
-- V40 dropped). Idempotent by an existence check against the half-open effective window
-- (RoleAssignment.isEffectiveAt / RoleAssignmentRepository.findEffectiveAssignmentsByUser) rather
-- than a natural-key ON CONFLICT: role_assignments' primary key is a generated id and (user_id,
-- role_id) is not unique on it — a user can hold non-overlapping windows of the same role — so
-- "already granted" here means "already effective now", the same test V40's migration uses.
INSERT INTO role_assignments (id, user_id, role_id, effective_start_date, created_at, created_by)
SELECT gen_random_uuid(), a.user_id, r.id, u.created_at, NOW(), 'seed-generator'
FROM (VALUES
    ('01960010-0000-7000-8000-000000000001'::uuid, 'SYSTEM_ADMINISTRATOR'),
    ('01960010-0000-7000-8000-000000000002'::uuid, 'LOCATION_MANAGER'),
    ('01960010-0000-7000-8000-000000000003'::uuid, 'DISPATCHER'),
    ('01960010-0000-7000-8000-000000000004'::uuid, 'DISPATCHER'),
    ('01960010-0000-7000-8000-000000000005'::uuid, 'TECHNICIAN'),
    ('01960010-0000-7000-8000-000000000006'::uuid, 'TECHNICIAN'),
    ('01960010-0000-7000-8000-000000000007'::uuid, 'TECHNICIAN'),
    ('01960010-0000-7000-8000-000000000008'::uuid, 'TECHNICIAN'),
    ('01960010-0000-7000-8000-000000000009'::uuid, 'TECHNICIAN'),
    ('01960010-0000-7000-8000-00000000000a'::uuid, 'TECHNICIAN'),
    ('01960010-0000-7000-8000-00000000000b'::uuid, 'TECHNICIAN'),
    ('01960010-0000-7000-8000-00000000000c'::uuid, 'SERVICE_ADVISOR'),
    ('01960010-0000-7000-8000-00000000000d'::uuid, 'SERVICE_ADVISOR'),
    ('01960010-0000-7000-8000-00000000000e'::uuid, 'ACCOUNTING_ASSOCIATE'),
    ('01960010-0000-7000-8000-00000000000f'::uuid, 'ACCOUNTING_ASSOCIATE'),
    ('01960010-0000-7000-8000-000000000010'::uuid, 'ACCOUNT_MANAGER'),
    ('01960010-0000-7000-8000-000000000011'::uuid, 'INVENTORY_LEAD'),
    ('01960010-0000-7000-8000-000000000012'::uuid, 'GENERAL_MANAGER'),
    ('01960010-0000-7000-8000-000000000013'::uuid, 'MANAGER'),
    ('01960010-0000-7000-8000-000000000014'::uuid, 'SHOP_MANAGER'),
    ('01960010-0000-7000-8000-000000000015'::uuid, 'INVENTORY_MANAGER'),
    -- raymond.chu (000016, INVENTORY_CONTROLLER) is deliberately absent here: the dedicated
    -- insert below grants him that role, and is kept separate to demonstrate the scope
    -- differentiation callout in its own comment.
    ('01960010-0000-7000-8000-000000000017'::uuid, 'CUSTOMER'),
    ('01960010-0000-7000-8000-000000000018'::uuid, 'SELF_SERVICE_CUSTOMER'),
    -- CONTROLLER is created by the concurrent §6 migration; JOIN silently skips this
    -- row until that role exists, so margaret.olsen's assignment is idempotently
    -- retried on every re-run of this repeatable migration.
    ('01960010-0000-7000-8000-000000000019'::uuid, 'CONTROLLER')
) AS a(user_id, role_name)
JOIN roles r ON r.name = a.role_name
JOIN users u ON u.id = a.user_id
WHERE NOT EXISTS (
    SELECT 1
    FROM role_assignments ra
    WHERE ra.user_id = a.user_id
      AND ra.role_id = r.id
      AND ra.effective_start_date <= NOW()
      AND (ra.effective_end_date IS NULL OR ra.effective_end_date > NOW())
);

-- Task 10 scope differentiation (docs/rbac-permission-role-audit-2026-08.md §7):
-- INVENTORY_MANAGER and INVENTORY_CONTROLLER hold identical permission sets by design
-- (#1373); they differ by reach. Since ADR-0061 §1 (#1868, #1875) reach is a property
-- of the role — roles.location_scope, seeded in V37 (INVENTORY_CONTROLLER = ALL,
-- INVENTORY_MANAGER = LOCATION) — combined with the holder's pos-people staffing
-- assignment. A role assignment carries no scope of its own (V38 dropped scope_type), so
-- raymond.chu's row below is a plain effective-dated assignment, mirrored after the
-- admin.alpha pattern in R__seed_reference_security.sql. felicia.grant (INVENTORY_MANAGER)
-- receives her location through pos-people's employee_location_assignment, not here. This is
-- raymond.chu's only role_assignments row — the general insert above omits him.
INSERT INTO role_assignments (id, user_id, role_id, effective_start_date, created_at, created_by)
SELECT '01960010-0000-7000-9000-000000000016'::uuid,
       '01960010-0000-7000-8000-000000000016'::uuid,
       r.id,
       CURRENT_DATE,
       NOW(),
       'seed-generator'
FROM roles r
WHERE r.name = 'INVENTORY_CONTROLLER'
ON CONFLICT (tenant_id, id) DO NOTHING;
