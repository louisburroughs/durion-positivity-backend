-- Consolidate person primary/secondary email into person_contact_point (EMAIL) and drop
-- the primary_email / secondary_email columns from person. Email now lives only in
-- person_contact_point (see PersonEmailService).

-- Backfill primary emails (is_primary = true).
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), p.id, 'EMAIL', p.primary_email, TRUE, NOW(), NOW()
FROM person p
WHERE p.primary_email IS NOT NULL
  AND btrim(p.primary_email) <> ''
  AND NOT EXISTS (
        SELECT 1 FROM person_contact_point cp
        WHERE cp.person_id = p.id
          AND cp.contact_type = 'EMAIL'
          AND lower(cp.value) = lower(p.primary_email));

-- Backfill secondary emails (is_primary = false).
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), p.id, 'EMAIL', p.secondary_email, FALSE, NOW(), NOW()
FROM person p
WHERE p.secondary_email IS NOT NULL
  AND btrim(p.secondary_email) <> ''
  AND NOT EXISTS (
        SELECT 1 FROM person_contact_point cp
        WHERE cp.person_id = p.id
          AND cp.contact_type = 'EMAIL'
          AND lower(cp.value) = lower(p.secondary_email));

ALTER TABLE person DROP COLUMN primary_email;
ALTER TABLE person DROP COLUMN secondary_email;
