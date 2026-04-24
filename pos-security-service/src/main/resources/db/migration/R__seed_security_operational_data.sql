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

-- Role assignments
INSERT INTO user_roles (user_id, role_id)
VALUES
    ('01960010-0000-7000-8000-000000000001', 'e9b3e6ba-af10-08ff-0376-1f2fa60d5093'), -- marcus.webb     → SYSTEM_ADMINISTRATOR
    ('01960010-0000-7000-8000-000000000002', '783422f6-84ab-f590-5d51-4fa87b06d679'), -- diana.rowe      → LOCATION_MANAGER
    ('01960010-0000-7000-8000-000000000003', 'f4b32a5f-edf2-2b8c-33e0-67f79bf423d2'), -- terrence.blake  → DISPATCHER
    ('01960010-0000-7000-8000-000000000004', 'f4b32a5f-edf2-2b8c-33e0-67f79bf423d2'), -- sandra.cruz     → DISPATCHER
    ('01960010-0000-7000-8000-000000000005', '190cbafe-4c1b-7e5f-768f-4b3c0d58a165'), -- kyle.brennan    → TECHNICIAN
    ('01960010-0000-7000-8000-000000000006', '190cbafe-4c1b-7e5f-768f-4b3c0d58a165'), -- deshawn.morris  → TECHNICIAN
    ('01960010-0000-7000-8000-000000000007', '190cbafe-4c1b-7e5f-768f-4b3c0d58a165'), -- carlos.ruiz     → TECHNICIAN
    ('01960010-0000-7000-8000-000000000008', '190cbafe-4c1b-7e5f-768f-4b3c0d58a165'), -- amber.nguyen    → TECHNICIAN
    ('01960010-0000-7000-8000-000000000009', '190cbafe-4c1b-7e5f-768f-4b3c0d58a165'), -- eddie.vasquez   → TECHNICIAN
    ('01960010-0000-7000-8000-00000000000a', '190cbafe-4c1b-7e5f-768f-4b3c0d58a165'), -- priya.patel     → TECHNICIAN
    ('01960010-0000-7000-8000-00000000000b', '190cbafe-4c1b-7e5f-768f-4b3c0d58a165'), -- james.okafor    → TECHNICIAN
    ('01960010-0000-7000-8000-00000000000c', 'f5e58579-e9de-574d-c2c5-56d3fd7e93f6'), -- rachel.kim      → SERVICE_ADVISOR
    ('01960010-0000-7000-8000-00000000000d', 'f5e58579-e9de-574d-c2c5-56d3fd7e93f6'), -- tyrone.williams → SERVICE_ADVISOR
    ('01960010-0000-7000-8000-00000000000e', 'b66cada1-845f-67e0-0da7-c9eb04b5692e'), -- olivia.chen     → ACCOUNTING_ASSOCIATE
    ('01960010-0000-7000-8000-00000000000f', 'b66cada1-845f-67e0-0da7-c9eb04b5692e'), -- harold.sanders  → ACCOUNTING_ASSOCIATE
    ('01960010-0000-7000-8000-000000000010', 'a781f7c1-e2aa-6ebb-7096-b53ac3575c92')  -- irene.torres    → ACCOUNT_MANAGER
ON CONFLICT (user_id, role_id) DO NOTHING;
