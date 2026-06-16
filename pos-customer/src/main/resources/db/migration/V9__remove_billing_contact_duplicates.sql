-- V9: Remove redundant commercial billing-contact persons (namespace 01960026-*).
--
-- The 01960026-* "billing contacts" were migrated from the legacy contact table and
-- are duplicate persons of the 01960025-* primary contacts (same human, same party).
-- The domain model keeps a single primary contact per commercial account, so the
-- billing duplicates are removed here along with their full dependency chain. Leaving
-- the person_party rows would resurface them as standalone "individual customers".
--
-- Namespace-scoped, one-time seed-data cleanup. Idempotent: nothing to delete on a
-- database that never had the legacy billing contacts.

-- 1. Roles for the billing-contact relationships.
DELETE FROM party_relationship_role
WHERE party_relationship_id IN (
    SELECT party_relationship_id FROM party_relationship
    WHERE to_person_id >= '01960026-0000-0000-0000-000000000000'::uuid
      AND to_person_id <  '01960027-0000-0000-0000-000000000000'::uuid
);

-- 2. The billing-contact relationships.
DELETE FROM party_relationship
WHERE to_person_id >= '01960026-0000-0000-0000-000000000000'::uuid
  AND to_person_id <  '01960027-0000-0000-0000-000000000000'::uuid;

-- 3. Their contact points.
DELETE FROM contact_point
WHERE person_id >= '01960026-0000-0000-0000-000000000000'::uuid
  AND person_id <  '01960027-0000-0000-0000-000000000000'::uuid;

-- 4. The person_party rows themselves.
DELETE FROM person_party
WHERE customer_id >= '01960026-0000-0000-0000-000000000000'::uuid
  AND customer_id <  '01960027-0000-0000-0000-000000000000'::uuid;
