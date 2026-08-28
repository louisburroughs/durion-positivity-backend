-- Retarget putaway rules still pointing at the destination the seeded ANY rule originally shipped
-- with (issue #1543).
--
-- 96dd346a-047c-86f5-3c9a-7c8cac53da86 is a site-level location id reused from a replenishment
-- policy row; it is not, and never was, a row in storage_location. While the seeded ANY rule
-- carried it, every putaway execution failed at PutawayValidationServiceImpl with "Destination
-- storage location does not exist" — and because ANY is the terminal fallback tier, that meant
-- every putaway on the environment, whatever the SKU.
--
-- 819a3e0bb retargeted the seed (R__seed_reference_inventory.sql) to
-- 01960004-0001-7000-8000-000000000003 ('Main Parts Shelf', seeded by pos-location
-- R__seed_location_2_operational_data.sql), but that seed inserts with ON CONFLICT (rule_id)
-- DO NOTHING: on any database where the rule row already existed, the repeatable migration re-runs
-- and changes nothing. The correction therefore only ever reached databases seeded from empty;
-- every environment seeded before 819a3e0bb (alpha among them) kept the broken destination
-- indefinitely. A repeatable additive seed cannot express a correction — this versioned migration
-- can, the same forward-only pattern V25/V26 used for the RBAC revokes.
--
-- Scoped by the bad value rather than by rule_id, so an operator-authored rule that copied the
-- destination is repaired too. Rows are matched on the one value known to be wrong, so this cannot
-- touch legitimate configuration; on databases already carrying the corrected seed it matches
-- nothing and is a no-op.

UPDATE putaway_rule
SET    destination_location_id = '01960004-0001-7000-8000-000000000003',
       updated_at = NOW()
WHERE  destination_location_id = '96dd346a-047c-86f5-3c9a-7c8cac53da86';
