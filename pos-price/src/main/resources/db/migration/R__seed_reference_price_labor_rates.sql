-- =============================================================
-- R__seed_reference_price_labor_rates.sql
-- Tier 0 — shop labor rates and the labor matrix (#1575 Tier 0, T0-3)
-- docs/SPEC-tier-0-durion-owned-service-data.md
-- =============================================================
-- ALL RATES AND PERCENTAGES HERE ARE INVENTED reference data, not any shop's real pricing.
-- Location ids match the Tier 0 shop ids used across the reference seeds.
--
-- What the set demonstrates:
--   * the widening ladder — a platform default, a platform TIRE_SERVICE rate, one location's
--     own default and that location's own tire rate, so all four Scope values are reachable;
--   * a matrix whose order matters — CORROSION (+15%) then AFTER_HOURS (+25%) then
--     FLEET_CONTRACT (-10%) compound in sequence, and reordering them changes the answer,
--     which is why sequence is stored rather than assumed.
--
-- Ids are md5-derived from the natural key so reruns are deterministic; ON CONFLICT keeps the
-- repeatable migration idempotent when the checksum changes.
SET TIME ZONE 'UTC';

INSERT INTO labor_rate (id, location_id, operation_category, currency, hourly_rate,
                        effective_from, effective_to, created_at, updated_at)
VALUES
    -- Platform defaults: what a location that has authored nothing charges.
    (md5('tier0:rate:platform:ALL')::uuid,          NULL, NULL,
     'USD', 125.0000, TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL, NOW(), NOW()),
    (md5('tier0:rate:platform:TIRE_SERVICE')::uuid, NULL, 'TIRE_SERVICE',
     'USD',  95.0000, TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL, NOW(), NOW()),
    -- Shop A has priced its own work, and prices tyre work below its own general rate.
    (md5('tier0:rate:shopA:ALL')::uuid,          '0198f2a1-0000-7000-8000-00000000000a'::uuid, NULL,
     'USD', 142.0000, TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL, NOW(), NOW()),
    (md5('tier0:rate:shopA:TIRE_SERVICE')::uuid, '0198f2a1-0000-7000-8000-00000000000a'::uuid, 'TIRE_SERVICE',
     'USD', 105.0000, TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL, NOW(), NOW()),
    -- Shop B has a general rate only, so its tyre work resolves to its own default rather than
    -- the platform tyre rate — the ladder is narrowest-scope-first, not category-first.
    (md5('tier0:rate:shopB:ALL')::uuid, '0198f2a1-0000-7000-8000-00000000000b'::uuid, NULL,
     'USD', 118.0000, TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO labor_rate_adjustment (id, location_id, operation_category, adjustment_code, description,
                                   adjustment_type, adjustment_value, sequence,
                                   effective_from, effective_to, created_at, updated_at)
VALUES
    (md5('tier0:lra:platform:CORROSION')::uuid, NULL, NULL, 'CORROSION',
     'Seized or corroded fasteners requiring heat, penetrant or extraction',
     'PERCENT', 15.0000, 10, TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL, NOW(), NOW()),
    (md5('tier0:lra:platform:RESTRICTED_ACCESS')::uuid, NULL, NULL, 'RESTRICTED_ACCESS',
     'Component access obstructed by aftermarket equipment or a prior repair',
     'PERCENT', 20.0000, 20, TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL, NOW(), NOW()),
    (md5('tier0:lra:platform:AFTER_HOURS')::uuid, NULL, NULL, 'AFTER_HOURS',
     'Work performed outside posted service hours',
     'PERCENT', 25.0000, 30, TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL, NOW(), NOW()),
    (md5('tier0:lra:platform:MOBILE_CALLOUT')::uuid, NULL, NULL, 'MOBILE_CALLOUT',
     'Flat call-out charge for work performed at the customer site',
     'FIXED', 35.0000, 40, TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL, NOW(), NOW()),
    -- Applied last on purpose: a contract discount is off the adjusted rate, not off the base,
    -- so its sequence is what makes it mean what the contract says.
    (md5('tier0:lra:platform:FLEET_CONTRACT')::uuid, NULL, NULL, 'FLEET_CONTRACT',
     'Negotiated fleet contract discount',
     'PERCENT', -10.0000, 90, TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL, NOW(), NOW()),
    -- Shop A prices commercial tyre work's extra handling as its own step.
    (md5('tier0:lra:shopA:COMMERCIAL_ASSEMBLY')::uuid,
     '0198f2a1-0000-7000-8000-00000000000a'::uuid, 'TIRE_SERVICE', 'COMMERCIAL_ASSEMBLY',
     'Commercial wheel and tyre assemblies requiring cage inflation and two-person handling',
     'PERCENT', 30.0000, 15, TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
