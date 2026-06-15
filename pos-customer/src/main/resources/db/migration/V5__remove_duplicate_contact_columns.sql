-- Issue #666: Remove redundant email/phone_number columns from AbstractParty subclasses.
-- PersonParty contact info lives in contact_point; CommercialParty contact persons live in contact.
--
-- Execution order:
--   1. Migrate person_party.email / phone_number → contact_point (idempotent guard)
--   2. Drop those columns from person_party
--   3. Drop email / phone_number from commercial_party (data preserved via contact + pos-people seed)

SET TIME ZONE 'UTC';

-- =========================================================================
-- Step 1: Migrate person_party.email → contact_point
-- =========================================================================

INSERT INTO contact_point (contact_point_id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT
    gen_random_uuid(),
    pp.customer_id,
    'EMAIL',
    pp.email,
    true,
    NOW(),
    NOW()
FROM person_party pp
WHERE pp.email IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM contact_point cp
      WHERE cp.person_id = pp.customer_id
        AND cp.contact_type = 'EMAIL'
        AND cp.value = pp.email
  );

-- =========================================================================
-- Step 2: Migrate person_party.phone_number → contact_point
-- =========================================================================

INSERT INTO contact_point (contact_point_id, person_id, contact_type, value, is_primary, created_at, updated_at)
SELECT
    gen_random_uuid(),
    pp.customer_id,
    'PHONE_MOBILE',
    pp.phone_number,
    true,
    NOW(),
    NOW()
FROM person_party pp
WHERE pp.phone_number IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM contact_point cp
      WHERE cp.person_id = pp.customer_id
        AND cp.contact_type IN ('PHONE_MOBILE', 'PHONE_HOME', 'PHONE_WORK')
        AND cp.value = pp.phone_number
  );

-- =========================================================================
-- Step 3: Drop columns from person_party
-- =========================================================================

ALTER TABLE person_party DROP COLUMN IF EXISTS email;
ALTER TABLE person_party DROP COLUMN IF EXISTS phone_number;

-- =========================================================================
-- Step 4: Drop columns from commercial_party
--         (data preserved via contact table + pos-people seed rows 01960025-*, 01960026-*)
-- =========================================================================

ALTER TABLE commercial_party DROP COLUMN IF EXISTS email;
ALTER TABLE commercial_party DROP COLUMN IF EXISTS phone_number;
