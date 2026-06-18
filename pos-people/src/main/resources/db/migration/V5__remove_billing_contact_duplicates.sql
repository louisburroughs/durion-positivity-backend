-- V5: Remove redundant commercial billing-contact persons (namespace 01960026-*).
--
-- These were duplicate persons of the 01960025-* primary contacts (same human),
-- migrated from the legacy contact taxonomy. They are no longer seeded (Group C
-- removed from the repeatable seed); the matching pos-customer person_party rows
-- are removed in pos-customer V9. Delete dependent rows first to satisfy the
-- person foreign keys. Idempotent; namespace-scoped one-time cleanup.

DELETE FROM person_contact_point
WHERE person_id >= '01960026-0000-0000-0000-000000000000'::uuid
  AND person_id <  '01960027-0000-0000-0000-000000000000'::uuid;

DELETE FROM person_phone_numbers
WHERE person_id >= '01960026-0000-0000-0000-000000000000'::uuid
  AND person_id <  '01960027-0000-0000-0000-000000000000'::uuid;

DELETE FROM person
WHERE id >= '01960026-0000-0000-0000-000000000000'::uuid
  AND id <  '01960027-0000-0000-0000-000000000000'::uuid;
