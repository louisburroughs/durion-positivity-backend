-- Repeatable seed migration for inventory reference data.
-- Source: durion/scripts/seed-generator/generated-seed-sql/004_catalog_pricing_inventory.sql
-- Notes:
-- - Includes only pos-inventory-owned tables.
-- - Adapted column names to current inventory Flyway schema.
SET TIME ZONE 'UTC';

-- Replenishment policies
INSERT INTO replenishment_policy (policy_id, location_id, itemsku, minimum_quantity, maximum_quantity, created_at, updated_at)
VALUES ('4db18cdb-755c-a13b-e253-201f79d997fe'::uuid, '96dd346a-047c-86f5-3c9a-7c8cac53da86'::uuid, 'OIL-5W30-5QT', 20, 40, NOW(), NOW())
ON CONFLICT (policy_id) DO NOTHING;

-- Putaway rules
INSERT INTO putaway_rule (rule_id, priority, criteria, destination_location_id, is_enabled, created_at, updated_at)
VALUES ('6f46541c-937d-397a-076f-63e092cabed6'::uuid, 1, '{"sku_prefix":"OIL-","location_id":"96dd346a-047c-86f5-3c9a-7c8cac53da86"}', '96dd346a-047c-86f5-3c9a-7c8cac53da86'::uuid, TRUE, NOW(), NOW())
ON CONFLICT (rule_id) DO NOTHING;

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
