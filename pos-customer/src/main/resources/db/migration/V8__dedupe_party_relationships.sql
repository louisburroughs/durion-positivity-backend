-- V8: Remove duplicate party_relationship rows.
--
-- V6 (contact-table decommission) created party_relationship rows from the legacy
-- contact table. When V6 ran before the repeatable seed in the same Flyway cycle,
-- the seed's (from_party_id, to_person_id) relationships did not yet exist, so V6's
-- NOT EXISTS guard matched nothing and inserted overlapping rows. The repeatable
-- seed then inserted its own rows (different ids, ON CONFLICT only on the PK), so a
-- party ended up with two relationships to the same contact person.
--
-- Keep the earliest relationship per (from_party_id, to_person_id) and delete the
-- rest, including their role rows. Idempotent: on a clean DB there is nothing to
-- remove. This only removes exact duplicate pairs; genuinely distinct contacts
-- (different to_person_id) are preserved.

-- Role rows for relationships that will be deleted.
DELETE FROM party_relationship_role
WHERE party_relationship_id IN (
    SELECT pr.party_relationship_id
    FROM party_relationship pr
    WHERE pr.party_relationship_id <> (
        SELECT keep.party_relationship_id
        FROM party_relationship keep
        WHERE keep.from_party_id = pr.from_party_id
          AND keep.to_person_id = pr.to_person_id
        ORDER BY keep.effective_start_date ASC, keep.created_at ASC, keep.party_relationship_id ASC
        LIMIT 1
    )
);

-- The duplicate relationships themselves.
DELETE FROM party_relationship pr
WHERE pr.party_relationship_id <> (
    SELECT keep.party_relationship_id
    FROM party_relationship keep
    WHERE keep.from_party_id = pr.from_party_id
      AND keep.to_person_id = pr.to_person_id
    ORDER BY keep.effective_start_date ASC, keep.created_at ASC, keep.party_relationship_id ASC
    LIMIT 1
);
