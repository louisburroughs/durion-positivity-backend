-- Fix pos-inventory startup schema validation failure (missing column [itemsku] in
-- table [purchase_suggestion]).
--
-- The PurchaseSuggestion entity's `itemSKU` field carries no explicit @Column mapping, so
-- Hibernate's CamelCaseToUnderscoresNamingStrategy resolves it. That strategy only inserts an
-- underscore before an uppercase letter that is followed by a lowercase one, so the trailing
-- all-caps acronym in `itemSKU` is NOT split: the field maps to column `itemsku`, exactly as
-- the sibling replenishment_policy.itemsku / replenishment_task.itemsku columns (also mapped
-- from an `itemSKU` field) were created in the V1 baseline.
--
-- V22 created this column as `item_sku` (with an underscore), which does not match the mapped
-- name, so schema validation aborts startup. Rename it to `itemsku` to align with the entity
-- and the mirrored replenishment tables. Postgres rewrites the dependent
-- idx_purchase_suggestion_sku_location_status index to follow the rename, so no index rebuild
-- is needed.
ALTER TABLE purchase_suggestion
    RENAME COLUMN item_sku TO itemsku;
