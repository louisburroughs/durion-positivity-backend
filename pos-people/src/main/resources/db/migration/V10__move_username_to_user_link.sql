-- Username is owned by pos-security (users) and associated to a person via
-- user_person_links. Remove person.username; ensure each legacy username exists as a
-- security user and is linked, so no username is lost.

-- 1. Create security users for person usernames not already present. Password is copied
--    from an existing non-admin user as a default (admin's credential is never reused);
--    these accounts should have their password reset before use.
INSERT INTO users (id, username, password)
SELECT gen_random_uuid(),
       p.username,
       COALESCE(
           (SELECT u2.password FROM users u2 WHERE lower(u2.username) <> 'admin' ORDER BY u2.username LIMIT 1),
           (SELECT u3.password FROM users u3 ORDER BY u3.username LIMIT 1))
FROM person p
WHERE p.username IS NOT NULL
  AND btrim(p.username) <> ''
  AND NOT EXISTS (SELECT 1 FROM users u WHERE lower(u.username) = lower(p.username));

-- 2. Link each person to its security user (user_person_links.user_id is unique → one
--    link per user; skip if that user is already linked).
INSERT INTO user_person_links (id, person_id, user_id, status, link_type, created_at, created_by)
SELECT gen_random_uuid(), p.id, u.id, 'ACTIVE', 'PRIMARY', NOW(), 'migration-v10'
FROM person p
JOIN users u ON lower(u.username) = lower(p.username)
WHERE p.username IS NOT NULL
  AND btrim(p.username) <> ''
  AND NOT EXISTS (SELECT 1 FROM user_person_links l WHERE l.user_id = u.id);

-- 3. Drop the column; username is now resolved via user_person_links → pos-security.
ALTER TABLE person DROP COLUMN username;
