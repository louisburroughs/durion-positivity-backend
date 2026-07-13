-- Repeatable seed migration for people-contact reference/bootstrap data.
-- Identity only: employment (employee, location assignments) lives in pos-people.
SET TIME ZONE 'UTC';

-- person (System Administrator) — admin.alpha's person record (identity only).
INSERT INTO person (id, first_name, last_name, created_at, updated_at)
VALUES (
    '583fa3b3-d1bf-a40d-8e21-8cd54424d5d0'::uuid,
    'System', 'Administrator',
    NOW(), NOW()
)
ON CONFLICT (id) DO NOTHING;

-- Email lives in person_contact_point (EMAIL); username is resolved via user_person_links.
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), '583fa3b3-d1bf-a40d-8e21-8cd54424d5d0'::uuid, 'EMAIL', 'admin.alpha@durionpos.org', TRUE, NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM person_contact_point
    WHERE person_id = '583fa3b3-d1bf-a40d-8e21-8cd54424d5d0'::uuid
      AND contact_type = 'EMAIL'
      AND value = 'admin.alpha@durionpos.org');

-- user_person_links (keyed by username).
INSERT INTO user_person_links (id, username, person_id, link_type, status, created_at, created_by)
VALUES (
    '4790360f-65ab-20e9-88e3-7bf9277bf2b9'::uuid,
    'admin.alpha',
    '583fa3b3-d1bf-a40d-8e21-8cd54424d5d0'::uuid,
    'PRIMARY',
    'ACTIVE',
    NOW(),
    'seed-generator'
)
ON CONFLICT (username) DO NOTHING;
