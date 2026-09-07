-- Operation-category dimension on source precedence (#1569 residual R1, sourcing plan Phase 3
-- item 2, SPEC-tier-0-durion-owned-service-data.md §3 D3).
--
-- Policy was keyed (time_type, source_code) only, so "tire ops prefer the manufacturer's
-- install time, mechanical retail prefers the aggregator's flat rate" was inexpressible as
-- data — the only thing separating a MANUFACTURER_INSTALL row from a RETAIL_FLAT_RATE row was
-- the hard-coded DEFAULT_TYPE_ORDER in the resolution service. Tier 0 needs it now: MICHELIN
-- publishes manufacturer install times for tire operations and must win there without winning
-- everywhere.
--
--   operation_category  NULL = applies to every category (the existing rows' meaning, which is
--                       why the column is nullable and the backfill is a no-op). A row stating
--                       a category applies only to it, and beats a category-less row for that
--                       category.
--
-- The unique key has to fold the null in: two rows for the same (time_type, source) — one
-- global, one for TIRE_SERVICE — is exactly the shape this feature exists to express, so the
-- old two-column UNIQUE would have blocked its own use case.

ALTER TABLE labor_time_source_policy ADD COLUMN operation_category varchar(32);

ALTER TABLE labor_time_source_policy ADD CONSTRAINT ck_ltsp_operation_category
    CHECK (operation_category IS NULL
           OR operation_category IN ('REPAIR', 'DIAGNOSTIC', 'MAINTENANCE', 'TIRE_SERVICE'));

-- NULLS NOT DISTINCT (PG15+) rather than a COALESCE expression index, so the key stays a plain
-- column list that ON CONFLICT (time_type, source_code, operation_category) can infer — the
-- repeatable policy seeds upsert on exactly that triple.
ALTER TABLE labor_time_source_policy DROP CONSTRAINT labor_time_source_policy_time_type_source_code_key;

ALTER TABLE labor_time_source_policy ADD CONSTRAINT ux_ltsp_key
    UNIQUE NULLS NOT DISTINCT (time_type, source_code, operation_category);
