-- ADR-0061 §1–§2 (#1878): materialised location-scope ancestor sets on the ext_location replica.
-- Location scope is assigned to a node and covers that node and every descendant; the check runs
-- in-process against these sets, never against pos-location per request. Two sets per location
-- because the hierarchy dimension is a role property: FINANCIAL walks the FINANCIAL parent chain,
-- OTHER walks the union of HOME_OFFICE/HEADQUARTERS/REGION/DISTRICT/PHYSICAL/ORGANIZATIONAL/SHIPPING.
-- Both are inclusive of the location itself so a directly assigned node matches by the same rule.
--
-- Stored as a comma-separated, sorted list of canonical UUID strings (see UuidSetConverter);
-- '' means "no ancestors known" and reads as deny. Existing rows are seeded self-only — the full
-- closure is materialised by LocationEventsListener from the ext_location_parent edges on the next
-- location fact, or all at once via the owner's POST .../facts/replay.
ALTER TABLE ext_location ADD COLUMN financial_ancestor_ids text NOT NULL DEFAULT '';
ALTER TABLE ext_location ADD COLUMN other_ancestor_ids text NOT NULL DEFAULT '';

UPDATE ext_location
SET financial_ancestor_ids = location_id::text,
    other_ancestor_ids = location_id::text;
