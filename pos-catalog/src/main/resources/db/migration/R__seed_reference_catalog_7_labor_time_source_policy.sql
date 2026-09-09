-- Tenant binding for the seed rows below (ADR-0062); transaction-local.
SELECT set_config('app.current_tenant', '01900000-0000-7000-8000-000000000001', true);

-- =============================================================
-- R__seed_reference_catalog_7_labor_time_source_policy.sql
-- Tier 1 — source precedence for the Tier 0 sources (#1575 Tier 0)
-- docs/SPEC-tier-0-durion-owned-service-data.md
-- =============================================================
-- Genuine tier-1 configuration under docs/DATA_SEED_STRATEGY.md §2: these rows are
-- (a) environment-invariant — the ranking is a platform decision, identical in alpha and prod;
-- (b) never published on a topic and never projected into another service's ext_* replica —
--     labor_time_source_policy is read only by pos-catalog's own resolution; and
-- (c) without an event-audited lifecycle — nothing authors policy rows through an API.
--
-- The Tier 0 operations, labor standards, service packages and labor rates that used to sit
-- beside this file were none of those things, and now load through the seed pipeline instead
-- (scripts/fixtures/seed/alpha/catalog/tier0-*.csv, price/labor-rate*.csv). Only the policy
-- stayed behind.
--
-- Read the ranking honestly, because it is easy to overstate what these rows do.
--
-- Resolution ranks owner, then vehicle specificity, then time-type preference, and only then
-- policy. So policy does NOT make MANUFACTURER_INSTALL beat RETAIL_FLAT_RATE (type preference
-- already does), and it does NOT make a MICHELIN wildcard beat an aggregator's vehicle-keyed
-- row (specificity already does). What it settles is two sources publishing the SAME time type
-- at EQUAL specificity: there, for TIRE_SERVICE, the tyre manufacturer's own number is the
-- better answer, and outside tyre work MICHELIN drops behind the aggregator — which is exactly
-- why one global precedence per source was not enough.
--
-- The policy is deliberately fuller than today's data can exercise: ux_sls_active_key still has
-- no source_code (spec §6, sourcing plan Phase 2 item 4), so two STORE sources cannot yet hold
-- the same time type at the same vehicle key at all. The rows are seeded now because Tier 0
-- introduces the second stored source; they become fully operative when that key widens.
--
-- No collision with the mock guide: every MOCKGUIDE MANUFACTURER_INSTALL row in the fixture is
-- vehicle-keyed (2019 Honda Civic, 2018 Toyota Camry, ...), while the MICHELIN rows below are
-- wildcards, so they answer the vehicles the mock's 20-vehicle matrix does not cover rather
-- than competing for the ones it does.
--
-- Ids are md5-derived from the natural key so reruns are deterministic; ON CONFLICT keeps the
-- repeatable migration idempotent when its checksum changes.
SET TIME ZONE 'UTC';

INSERT INTO labor_time_source_policy (id, time_type, source_code, operation_category, precedence, enabled, created_at, updated_at)
VALUES
    (md5('ltsp:MANUFACTURER_INSTALL:MICHELIN:TIRE_SERVICE')::uuid, 'MANUFACTURER_INSTALL', 'MICHELIN', 'TIRE_SERVICE',  10, true, NOW(), NOW()),
    (md5('ltsp:MANUFACTURER_INSTALL:MICHELIN:')::uuid,             'MANUFACTURER_INSTALL', 'MICHELIN', NULL,           300, true, NOW(), NOW()),
    (md5('ltsp:DURION_STANDARD:DURION:TIRE_SERVICE')::uuid,        'DURION_STANDARD',      'DURION',   'TIRE_SERVICE',  50, true, NOW(), NOW())
ON CONFLICT (tenant_id, time_type, source_code, operation_category) DO NOTHING;
