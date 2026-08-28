-- Repeatable seed migration for inventory reference data.
-- Notes:
-- - Includes only pos-inventory-owned, environment-invariant configuration (tier 1,
--   docs/DATA_SEED_STRATEGY.md §2).
-- - Issue #1554: the replenishment policy, the terminal ANY putaway rule, and the
--   initial GOODS_RECEIPT inventory_ledger_entry stock previously seeded here all
--   referenced pos-location's Flyway-seeded storage-location UUIDs. That seed is
--   retired in favour of the API-driven pipeline (generated ids, facts emitted), so
--   those rows moved with it: the terminal ANY rule is the fixture pack's
--   scripts/fixtures/seed/alpha/inventory/putaway-rules.csv row (destination resolved
--   by name at load time), and initial stock is scripts/fixtures/seed/alpha/inventory/
--   on-hand.csv driven through bulk-ingest + adjustment approval. Nothing here may
--   reference a storage-location or site id again.
SET TIME ZONE 'UTC';

-- Approval thresholds
INSERT INTO approval_threshold_config (
    config_id,
    approval_tier,
    unit_variance_threshold,
    value_variance_threshold,
    percentage_variance_threshold,
    active,
    created_at,
    updated_at
)
VALUES ('36458d57-6d89-5f33-ab7b-cca72774fd21'::uuid, 'TIER_1_MANAGER', 0, 100, 0, TRUE, NOW(), NOW())
ON CONFLICT (config_id) DO NOTHING;
