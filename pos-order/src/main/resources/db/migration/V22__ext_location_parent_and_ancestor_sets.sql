-- ADR-0061 §1–§2 (#1872): location-scope hierarchy on the ext_location replica.
--
-- 1. Typed location-parent edges carried on location.location.updated facts (ADR-0044 §6),
--    mirroring pos-inventory's, pos-people's and pos-invoice's ext_location_parent. One parent per
--    (child, parentType) upstream; every location fact replaces the child's full edge set.
CREATE TABLE IF NOT EXISTS ext_location_parent (
    child_id UUID NOT NULL,
    parent_type VARCHAR(64) NOT NULL,
    parent_id UUID NOT NULL,
    CONSTRAINT ext_location_parent_pkey PRIMARY KEY (child_id, parent_type)
);
CREATE INDEX IF NOT EXISTS idx_ext_location_parent_parent ON ext_location_parent (parent_id, parent_type);

-- 2. Materialised ancestor sets. Location scope is assigned to a node and covers that node and
--    every descendant; the check runs in-process against these sets, never against pos-location
--    per request. Two sets per location because the hierarchy dimension is a role property:
--    FINANCIAL walks the FINANCIAL parent chain, OTHER walks the union of
--    HOME_OFFICE/HEADQUARTERS/REGION/DISTRICT/PHYSICAL/ORGANIZATIONAL/SHIPPING. Both are inclusive
--    of the location itself so a directly assigned node matches by the same rule.
--
--    Stored as a comma-separated, sorted list of canonical UUID strings (see UuidSetConverter);
--    '' means "no ancestors known" and reads as deny. Existing rows are seeded self-only — the
--    full closure is materialised by LocationEventsListener from the edges on the next location
--    fact, or all at once via the owner's POST .../facts/replay.
ALTER TABLE ext_location ADD COLUMN financial_ancestor_ids TEXT NOT NULL DEFAULT '';
ALTER TABLE ext_location ADD COLUMN other_ancestor_ids TEXT NOT NULL DEFAULT '';

UPDATE ext_location
SET financial_ancestor_ids = location_id::text,
    other_ancestor_ids = location_id::text;
