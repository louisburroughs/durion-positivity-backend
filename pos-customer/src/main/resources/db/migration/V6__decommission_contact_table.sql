-- V6: Decommission the legacy contact table.
--
-- The contact table was a flat denormalised store for commercial party contacts.
-- All contact data is now held in party_relationship + party_relationship_role,
-- with contact points in contact_point and persons in person_party.
--
-- Migration steps (all idempotent via ON CONFLICT / WHERE NOT EXISTS):
--   1. Ensure a person_party row exists for each contact person.
--   2. Copy email addresses into contact_point.
--   3. Copy phone numbers into contact_point.
--   4. Create party_relationship rows for active contacts.
--   5. Create party_relationship_role rows (PRIMARY_CONTACT) for new relations.
--   6. Drop the contact table.
--
-- On a fresh database the contact table is empty at this point
-- (versioned migrations run before repeatable seeds), so steps 1-5 are no-ops.
-- On an existing database with data this performs the live migration.

SET TIME ZONE 'UTC';

-- Step 1: person_party rows for contact persons not yet present
INSERT INTO person_party (customer_id, person_id, customer_number,
                          first_name, last_name, primary_address,
                          status, tier, tier_manual_override,
                          preferred_contact_method, created_at, updated_at)
SELECT c.person_id,
       c.person_id,
       'CUST-MIG-' || c.person_id::text,
       c.first_name,
       c.last_name,
       NULL,
       0,
       0,
       false,
       'EMAIL',
       NOW(),
       NOW()
FROM contact c
WHERE NOT EXISTS (SELECT 1 FROM person_party pp WHERE pp.customer_id = c.person_id)
ON CONFLICT (customer_id) DO NOTHING;

-- Step 2: email contact_point rows
INSERT INTO contact_point (contact_point_id, person_id, contact_type, value,
                           is_primary, created_at, updated_at)
SELECT gen_random_uuid(),
       c.person_id,
       'EMAIL',
       c.email,
       true,
       NOW(),
       NOW()
FROM contact c
WHERE c.email IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM contact_point cp
    WHERE cp.person_id = c.person_id AND cp.contact_type = 'EMAIL'
  );

-- Step 3: phone contact_point rows
INSERT INTO contact_point (contact_point_id, person_id, contact_type, value,
                           is_primary, created_at, updated_at)
SELECT gen_random_uuid(),
       c.person_id,
       'PHONE_WORK',
       c.phone_number,
       true,
       NOW(),
       NOW()
FROM contact c
WHERE c.phone_number IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM contact_point cp
    WHERE cp.person_id = c.person_id AND cp.contact_type = 'PHONE_WORK'
  );

-- Step 4: party_relationship rows for active contacts
INSERT INTO party_relationship (party_relationship_id, from_party_id, to_person_id,
                                is_primary_billing_contact, effective_start_date, effective_end_date,
                                created_at, updated_at)
SELECT gen_random_uuid(),
       c.party_id,
       c.person_id,
       false,
       CURRENT_DATE,
       NULL,
       NOW(),
       NOW()
FROM contact c
WHERE c.active = true
  AND NOT EXISTS (
    SELECT 1 FROM party_relationship pr
    WHERE pr.from_party_id = c.party_id AND pr.to_person_id = c.person_id
  );

-- Step 5: party_relationship_role rows (PRIMARY_CONTACT) for newly created relations
INSERT INTO party_relationship_role (party_relationship_id, role_type)
SELECT pr.party_relationship_id, 'PRIMARY_CONTACT'
FROM party_relationship pr
WHERE pr.from_party_id IN (SELECT DISTINCT party_id FROM contact)
  AND NOT EXISTS (
    SELECT 1 FROM party_relationship_role prr
    WHERE prr.party_relationship_id = pr.party_relationship_id
  )
ON CONFLICT DO NOTHING;

-- Step 6: drop the contact table
DROP TABLE IF EXISTS contact;
