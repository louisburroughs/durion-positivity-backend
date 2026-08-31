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
ON CONFLICT (username) DO UPDATE SET password = EXCLUDED.password, enabled = EXCLUDED.enabled;

-- Role assignments (resolved by role name to tolerate variable UUIDs from versioned migrations)
--
-- #1613 D8: these joins now resolve only for roles Flyway still creates. On a fresh database the
-- rest of the roles do not exist yet when this runs, so those rows match nothing and no assignment
-- is made. That is covered rather than broken: scripts/fixtures/seed/alpha/security/users.csv
-- provisions the same 25 accounts with the same roles through the SECURITY_USER loader, which runs
-- after the role load. On an environment that already has the roles this seed applies as before.
INSERT INTO user_roles (user_id, role_id)
SELECT a.user_id, r.id
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
    ('01960010-0000-7000-8000-000000000016'::uuid, 'INVENTORY_CONTROLLER'),
    ('01960010-0000-7000-8000-000000000017'::uuid, 'CUSTOMER'),
    ('01960010-0000-7000-8000-000000000018'::uuid, 'SELF_SERVICE_CUSTOMER'),
    -- CONTROLLER is created by the concurrent §6 migration; JOIN silently skips this
    -- row until that role exists, so margaret.olsen's assignment is idempotently
    -- retried on every re-run of this repeatable migration.
    ('01960010-0000-7000-8000-000000000019'::uuid, 'CONTROLLER')
) AS a(user_id, role_name)
JOIN roles r ON r.name = a.role_name
ON CONFLICT (user_id, role_id) DO NOTHING;

-- Task 10 scope differentiation (docs/rbac-permission-role-audit-2026-08.md §7):
-- INVENTORY_MANAGER and INVENTORY_CONTROLLER hold identical permission sets by design
-- (#1373) — location vs. global reach lives in role_assignments.scope_type, mirrored
-- here after the admin.alpha pattern in R__seed_reference_security.sql. scope_type has
-- no DB check constraint (VARCHAR(20) in V1__baseline_rbac_schema.sql), but the
-- application layer (ScopeType enum / RoleManagementServiceImpl) only accepts GLOBAL
-- or LOCATION, and a LOCATION assignment requires at least one row in
-- role_assignment_scope_locations to be meaningful. pos-security-service does not own
-- a locations table (locations live in pos-location's own schema — no cross-service
-- FKs per this repo's architecture), so there is no location fixture this migration can
-- seed or safely reference. raymond.chu (INVENTORY_CONTROLLER) therefore gets the
-- GLOBAL row below; felicia.grant's (INVENTORY_MANAGER) LOCATION-scoped row is deferred
-- until a location fixture this service can reference exists.
INSERT INTO role_assignments (id, user_id, role_id, scope_type, effective_start_date, created_at, created_by)
SELECT '01960010-0000-7000-9000-000000000016'::uuid,
       '01960010-0000-7000-8000-000000000016'::uuid,
       r.id,
       'GLOBAL',
       CURRENT_DATE,
       NOW(),
       'seed-generator'
FROM roles r
WHERE r.name = 'INVENTORY_CONTROLLER'
ON CONFLICT (id) DO NOTHING;
