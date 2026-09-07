-- =============================================================
-- R__seed_reference_catalog_7_tier0_durion_owned.sql
-- Tier 0 — Durion-owned service data (#1575 Tier 0, T0-1)
-- docs/SPEC-tier-0-durion-owned-service-data.md
-- =============================================================
-- #1575 names Tier 0 as the data Durion owns outright: tire service operations,
-- Michelin-specific procedures, fleet requirements, dealer-created operations. None of it
-- comes from a licensed guide, which is why it can be built while Tier 2 procurement runs.
--
-- ALL NUMBERS IN THIS FILE ARE INVENTED. Every labor standard below carries
-- source_revision = 'tier0-fake-2026-09' (spec D6) so the whole fake set is identifiable
-- and removable in one statement:
--
--     DELETE FROM service_labor_standard WHERE source_revision = 'tier0-fake-2026-09';
--
-- Nothing here is, or is derived from, MOTOR / Mitchell 1 / ALLDATA / OEM warranty data.
-- The shapes are real; the hours are placeholders that make the pipeline demonstrable
-- end to end before any licensing spend.
--
-- Ids are md5-derived from the natural key so reruns are deterministic, and every insert is
-- ON CONFLICT DO NOTHING so the repeatable migration stays idempotent when its checksum
-- changes — the same convention as R__seed_reference_catalog_6_labor_guide.sql.
SET TIME ZONE 'UTC';

-- ─────────────────────────────────────────────────────────────
-- 1. Tier 0 operations
-- ─────────────────────────────────────────────────────────────
-- What a tire-and-fleet provider actually sells and no mechanical labor guide publishes.
-- These carry default_labor_hours, unlike the 50 general services seeded in file 3: there the
-- hours would have been invented stand-ins for guide data we do not have, while here the
-- vehicle-agnostic hours ARE the Durion-owned answer for an operation that varies little by
-- vehicle. The vehicle-keyed rows in §2 still take precedence wherever they apply.

INSERT INTO service (id, name, short_description, long_description,
                     operation_code, operation_category, default_labor_hours, created_at, updated_at)
SELECT md5('tier0:svc:' || v.operation_code)::uuid,
       v.name, v.short_description, v.long_description,
       v.operation_code, v.operation_category, v.default_labor_hours, NOW(), NOW()
FROM (VALUES
    -- Tire service
    ('TPMS-SENSOR-SERVICE', 'TPMS Service Kit - Set of 4', 'Rebuild and reset four TPMS sensors',
     'Replace TPMS service kits (valve core, grommet, nut, cap) on all four sensors, test sensor transmission, relearn sensor positions to the vehicle.',
     'TIRE_SERVICE', 0.6),
    ('TPMS-SENSOR-REPLACE-SINGLE', 'TPMS Sensor Replacement - Single', 'Replace one TPMS sensor',
     'Dismount tire, replace one failed TPMS sensor, remount and balance, program sensor id to the vehicle, verify transmission and clear the TPMS lamp.',
     'TIRE_SERVICE', 0.8),
    ('TIRE-REPAIR-PATCH-PLUG', 'Tire Repair - Patch and Plug', 'Interior patch-plug puncture repair',
     'Dismount tire, inspect the casing interior for run-flat damage, buff and apply a combination patch-plug unit to a repairable tread-area puncture, remount, balance and torque to spec.',
     'TIRE_SERVICE', 0.7),
    ('ROAD-FORCE-BALANCE-SET-4', 'Road Force Balance - Set of 4', 'Road force variation balance, four wheels',
     'Measure road force variation on all four assemblies, match-mount tire to rim high point where variation exceeds tolerance, rebalance and record before/after readings.',
     'TIRE_SERVICE', 1.2),
    ('LUG-TORQUE-RECHECK', 'Lug Torque Re-check', 'Post-service torque verification',
     'Re-torque all wheel fasteners to manufacturer specification with a calibrated torque wrench, inspect for seating and stud damage, record the reading.',
     'TIRE_SERVICE', 0.3),
    ('TIRE-INSTALL-LT-SET-4', 'Tire Installation - Light Truck Set of 4', 'Mount and balance four LT tires',
     'Mount and balance four light-truck tires including heavier assemblies and higher inflation pressures, install valve stems, reset TPMS, torque to LT specification.',
     'TIRE_SERVICE', 1.6),
    ('TIRE-INSTALL-COMMERCIAL-SINGLE', 'Tire Installation - Commercial Single', 'Mount one commercial tire',
     'Mount one commercial truck tire on a steel or aluminium rim using a commercial changer, inspect the rim and lock ring, inflate in a safety cage, torque to commercial specification.',
     'TIRE_SERVICE', 0.9),
    ('NITROGEN-FILL-SET-4', 'Nitrogen Inflation - Set of 4', 'Purge and fill four tires with nitrogen',
     'Purge ambient air from all four tires, fill with nitrogen to specification, verify purity, fit nitrogen valve caps.',
     'TIRE_SERVICE', 0.4),
    -- Michelin-specific procedures
    ('MICHELIN-CASING-INSPECTION', 'Michelin Casing Inspection', 'Michelin retreadability casing inspection',
     'Inspect a Michelin casing against the manufacturer retreadability criteria: bead condition, sidewall separations, repair history, shoulder wear and DOT age; record the disposition for the casing credit programme.',
     'TIRE_SERVICE', 0.5),
    ('MICHELIN-RETREAD-EVALUATION', 'Michelin Retread Evaluation', 'Evaluate a casing for the retread programme',
     'Shearography and visual evaluation of a Michelin casing for the retread programme, document findings and photograph defects, submit the evaluation record.',
     'TIRE_SERVICE', 0.8),
    -- Fleet requirements
    ('FLEET-PM-A-SERVICE', 'Fleet PM - A Service', 'Fleet preventive maintenance, A interval',
     'Fleet A-interval preventive maintenance: oil and filter, chassis lubrication, fluid levels, lamp and wiper check, tyre pressures and tread depths recorded to the fleet log.',
     'MAINTENANCE', 1.4),
    ('FLEET-PM-B-SERVICE', 'Fleet PM - B Service', 'Fleet preventive maintenance, B interval',
     'Fleet B-interval preventive maintenance: everything in the A service plus brake measurement, suspension and steering inspection, exhaust inspection, battery load test and a road test.',
     'MAINTENANCE', 2.8),
    ('DOT-ANNUAL-INSPECTION', 'DOT Annual Inspection', 'Federal annual vehicle inspection',
     'Federal annual inspection to 49 CFR 396 Appendix A: brakes, coupling devices, exhaust, fuel system, lighting, steering, suspension, frame, tyres, wheels and rims; complete and file the inspection report.',
     'MAINTENANCE', 1.9),
    ('FLEET-TREAD-DEPTH-AUDIT', 'Fleet Tread Depth Audit', 'Record tread depths across a unit',
     'Measure and record tread depth at three points across every tyre on the unit, note irregular wear patterns and pressures, and file the audit against the fleet account.',
     'MAINTENANCE', 0.4)
) AS v (operation_code, name, short_description, long_description, operation_category, default_labor_hours)
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────
-- 2. Durion-owned vehicle-keyed labor standards
-- ─────────────────────────────────────────────────────────────
-- DURION_STANDARD / source DURION: the number Durion has decided for its own operations.
--
-- Three cases are seeded deliberately so the downstream arithmetic is exercised by Tier 0
-- data and not only by mock-guide data:
--
--   overlap    WHEEL-OFF joins the four-tire operations to the front brake operation the mock
--              guide already groups — wheels come off once, and the workorder total must not
--              bill the setup twice.
--   included   TIRE-INSTALL-SET-4 and TIRE-INSTALL-LT-SET-4 include the balance and the TPMS
--              service; those lines must contribute zero when installed alongside.
--   widening   FLEET-PM-A-SERVICE has a wildcard row AND a heavier Ford F-350 row, so the
--              match-grade ladder (MODEL_LEVEL vs ENGINE_WILDCARD) has a Tier 0 case.

INSERT INTO service_labor_standard (
    id, service_id, vehicle_year, make, model, submodel, engine_code,
    labor_hours, time_type, overlap_group, included_op_codes,
    owner_scope, owner_location_id,
    source_code, source_revision, published_at, created_at, updated_at)
SELECT md5('tier0:sls:' || v.operation_code || ':' || v.time_type || ':' || v.key_tag)::uuid,
       s.id, v.vehicle_year, v.make, v.model, NULL, NULL,
       v.labor_hours, v.time_type, v.overlap_group,
       CASE WHEN v.included_op_codes = '' THEN NULL
            ELSE string_to_array(v.included_op_codes, ',') END,
       'PLATFORM', NULL,
       v.source_code, 'tier0-fake-2026-09', DATE '2026-09-01', NOW(), NOW()
FROM (VALUES
    -- Durion-owned tire operations, passenger baseline
    ('TIRE-ROTATION',              'wild', NULL::varchar, NULL::varchar, NULL::varchar, 0.5, 'DURION_STANDARD', 'WHEEL-OFF', '',                          'DURION'),
    ('WHEEL-BALANCE-SET-4',        'wild', NULL,  NULL,   NULL,     0.8, 'DURION_STANDARD',      'WHEEL-OFF', '',                                          'DURION'),
    ('TIRE-INSTALL-SET-4',         'wild', NULL,  NULL,   NULL,     1.3, 'DURION_STANDARD',      'WHEEL-OFF', 'WHEEL-BALANCE-SET-4,TPMS-SENSOR-SERVICE',   'DURION'),
    ('TIRE-INSTALL-LT-SET-4',      'wild', NULL,  NULL,   NULL,     1.6, 'DURION_STANDARD',      'WHEEL-OFF', 'WHEEL-BALANCE-SET-4,TPMS-SENSOR-SERVICE',   'DURION'),
    ('TPMS-SENSOR-SERVICE',        'wild', NULL,  NULL,   NULL,     0.6, 'DURION_STANDARD',      'WHEEL-OFF', '',                                          'DURION'),
    ('TPMS-SENSOR-REPLACE-SINGLE', 'wild', NULL,  NULL,   NULL,     0.8, 'DURION_STANDARD',      NULL,        '',                                          'DURION'),
    ('TIRE-REPAIR-PATCH-PLUG',     'wild', NULL,  NULL,   NULL,     0.7, 'DURION_STANDARD',      NULL,        '',                                          'DURION'),
    ('ROAD-FORCE-BALANCE-SET-4',   'wild', NULL,  NULL,   NULL,     1.2, 'DURION_STANDARD',      'WHEEL-OFF', 'WHEEL-BALANCE-SET-4',                       'DURION'),
    ('LUG-TORQUE-RECHECK',         'wild', NULL,  NULL,   NULL,     0.3, 'DURION_STANDARD',      NULL,        '',                                          'DURION'),
    ('NITROGEN-FILL-SET-4',        'wild', NULL,  NULL,   NULL,     0.4, 'DURION_STANDARD',      NULL,        '',                                          'DURION'),
    -- Commercial assemblies are heavier work than passenger ones; keyed to the makes that carry them
    ('TIRE-INSTALL-COMMERCIAL-SINGLE', 'wild',   NULL,  NULL,        NULL, 0.9, 'DURION_STANDARD', NULL, '', 'DURION'),
    ('TIRE-INSTALL-COMMERCIAL-SINGLE', 'frtl',   NULL,  'Freightliner', NULL, 1.3, 'DURION_STANDARD', NULL, '', 'DURION'),
    -- Fleet operations: wildcard baseline plus one heavier vehicle-keyed row
    ('FLEET-PM-A-SERVICE',      'wild',  NULL,  NULL,   NULL,    1.4, 'DURION_STANDARD', NULL, '', 'DURION'),
    ('FLEET-PM-A-SERVICE',      'f350',  NULL,  'Ford', 'F-350', 2.0, 'DURION_STANDARD', NULL, '', 'DURION'),
    ('FLEET-PM-B-SERVICE',      'wild',  NULL,  NULL,   NULL,    2.8, 'DURION_STANDARD', NULL, 'FLEET-PM-A-SERVICE', 'DURION'),
    ('DOT-ANNUAL-INSPECTION',   'wild',  NULL,  NULL,   NULL,    1.9, 'DURION_STANDARD', NULL, '', 'DURION'),
    ('FLEET-TREAD-DEPTH-AUDIT', 'wild',  NULL,  NULL,   NULL,    0.4, 'DURION_STANDARD', NULL, '', 'DURION'),
    -- Michelin manufacturer install / procedure times (source MICHELIN, still invented)
    ('MICHELIN-CASING-INSPECTION',  'wild', NULL, NULL, NULL, 0.5, 'MANUFACTURER_INSTALL', NULL, '', 'MICHELIN'),
    ('MICHELIN-RETREAD-EVALUATION', 'wild', NULL, NULL, NULL, 0.8, 'MANUFACTURER_INSTALL', NULL, '', 'MICHELIN'),
    ('TIRE-INSTALL-SET-4',          'wild', NULL, NULL, NULL, 1.1, 'MANUFACTURER_INSTALL', 'WHEEL-OFF', 'WHEEL-BALANCE-SET-4', 'MICHELIN'),
    ('TIRE-INSTALL-LT-SET-4',       'wild', NULL, NULL, NULL, 1.5, 'MANUFACTURER_INSTALL', 'WHEEL-OFF', 'WHEEL-BALANCE-SET-4', 'MICHELIN')
) AS v (operation_code, key_tag, vehicle_year, make, model, labor_hours, time_type, overlap_group, included_op_codes, source_code)
JOIN service s ON s.operation_code = v.operation_code
ON CONFLICT (id) DO NOTHING;

-- ─────────────────────────────────────────────────────────────
-- 3. Source precedence for the Tier 0 sources
-- ─────────────────────────────────────────────────────────────
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

INSERT INTO labor_time_source_policy (id, time_type, source_code, operation_category, precedence, enabled, created_at, updated_at)
VALUES
    (md5('ltsp:MANUFACTURER_INSTALL:MICHELIN:TIRE_SERVICE')::uuid, 'MANUFACTURER_INSTALL', 'MICHELIN', 'TIRE_SERVICE',  10, true, NOW(), NOW()),
    (md5('ltsp:MANUFACTURER_INSTALL:MICHELIN:')::uuid,             'MANUFACTURER_INSTALL', 'MICHELIN', NULL,           300, true, NOW(), NOW()),
    (md5('ltsp:DURION_STANDARD:DURION:TIRE_SERVICE')::uuid,        'DURION_STANDARD',      'DURION',   'TIRE_SERVICE',  50, true, NOW(), NOW())
ON CONFLICT (time_type, source_code, operation_category) DO NOTHING;
