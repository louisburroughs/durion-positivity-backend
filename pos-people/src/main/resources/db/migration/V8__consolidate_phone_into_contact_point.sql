-- Consolidate person phone numbers into person_contact_point (PHONE_WORK) and drop
-- the legacy person_phone_numbers element-collection table. Phone numbers now live
-- only in person_contact_point (see PersonWorkPhoneService).

-- Backfill: each person_phone_numbers value becomes a PHONE_WORK contact point,
-- unless that value already exists as a PHONE_WORK point for the same person.
INSERT INTO person_contact_point (id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT gen_random_uuid(), ppn.person_id, 'PHONE_WORK', ppn.phone_numbers, FALSE, NOW(), NOW()
FROM person_phone_numbers ppn
WHERE ppn.phone_numbers IS NOT NULL
  AND btrim(ppn.phone_numbers) <> ''
  AND NOT EXISTS (
        SELECT 1
        FROM person_contact_point cp
        WHERE cp.person_id = ppn.person_id
          AND cp.contact_type = 'PHONE_WORK'
          AND cp.value = ppn.phone_numbers);

DROP TABLE person_phone_numbers;
