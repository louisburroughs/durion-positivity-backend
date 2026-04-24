-- Repeatable seed migration for pos-security-service operational users.
-- 16 employees across 7 roles for Durion Positivity (medium truck mechanical repair corporation).
-- Password: abc123456! (BCrypt rounds=10, embedded as literal — no Flyway placeholder used)
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
    ('01960010-0000-7000-8000-000000000010', 'irene.torres',    '$2y$10$r2Vph.8y7daYEIMfBfDp/eGd0sAIwewYL9sBAAN2eonKnAYBJSfc.', true)
ON CONFLICT (username) DO UPDATE SET password = EXCLUDED.password, enabled = EXCLUDED.enabled;

-- Role assignments (resolved by role name to tolerate variable UUIDs from versioned migrations)
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
    ('01960010-0000-7000-8000-000000000010'::uuid, 'ACCOUNT_MANAGER')
) AS a(user_id, role_name)
JOIN roles r ON r.name = a.role_name
ON CONFLICT (user_id, role_id) DO NOTHING;
