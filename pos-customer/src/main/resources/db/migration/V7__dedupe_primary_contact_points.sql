-- V7: Repair duplicate primary contact points and prevent recurrence.
--
-- On a database that ran V6 (legacy contact migration) AND the repeatable seed,
-- a person could end up with two is_primary contact points of the same type:
-- one inserted by V6 from the old contact table, one by the seed (the seed only
-- guards ON CONFLICT (contact_point_id), so a different id slips through).
-- This made the single-result primary lookup throw NonUniqueResultException and
-- 500 the GET /v1/crm/commercial-accounts/{partyId}/contacts endpoint.
--
-- The read path now tolerates it (findFirst...OrderByCreatedAtAsc), but the data
-- is repaired here and a partial unique index prevents the condition recurring.
-- On a fresh database V6 is a no-op, so no duplicates exist and the UPDATE is a
-- no-op; the index still applies.

SET TIME ZONE 'UTC';

-- Demote every primary except the earliest-created one per (person_id, contact_type).
UPDATE contact_point cp
SET is_primary = false,
    updated_at = NOW()
WHERE cp.is_primary = true
  AND cp.contact_point_id <> (
      SELECT keep.contact_point_id
      FROM contact_point keep
      WHERE keep.person_id = cp.person_id
        AND keep.contact_type = cp.contact_type
        AND keep.is_primary = true
      ORDER BY keep.created_at ASC, keep.contact_point_id ASC
      LIMIT 1
  );

-- Enforce at most one primary contact point per person and contact type.
CREATE UNIQUE INDEX IF NOT EXISTS ux_contact_point_one_primary_per_type
    ON contact_point (person_id, contact_type)
    WHERE is_primary;
