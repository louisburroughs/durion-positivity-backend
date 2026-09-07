-- =============================================================
-- R__seed_reference_catalog_8_tier0_packages.sql
-- Tier 0 — service packages and fleet requirement sets (#1575 Tier 0, T0-4)
-- docs/SPEC-tier-0-durion-owned-service-data.md
-- =============================================================
-- ALL PACKAGE HOURS HERE ARE INVENTED, like the labor standards in file 7. Package hours are
-- AUTHORED rather than derived (spec D4): the overlap arithmetic that turns member times into a
-- total lives in pos-workorder's EstimatedLaborService, and a shop prices a package as a number
-- it chose, not as a rollup of its parts. Compare TIRE-INSTALL-PKG-4 below (1.6 hr) against the
-- naive sum of its members (1.3 + 0.8 + 0.6 = 2.7): the package is cheaper precisely because the
-- wheels come off once, which is the whole reason a package is a thing a shop sells.
--
-- The last entry is a FLEET REQUIREMENT SET rather than an offering: fleet_party_id points at a
-- commercial account, and its members are required, so it is what that account gets on every
-- visit rather than something a writer chooses to add.
--
-- The fleet party id is a reference-data placeholder in the same shape as the seeded shop ids.
-- It does not have to resolve in pos-customer for the package to be readable — there are no
-- cross-service foreign keys, and a requirement set with an unrecognised owner simply never
-- matches a real fleet's query.
--
-- Ids are md5-derived from the natural key so reruns are deterministic; ON CONFLICT keeps the
-- repeatable migration idempotent when the checksum changes.
SET TIME ZONE 'UTC';

INSERT INTO service_package (id, package_code, name, description, owner_scope, owner_location_id,
                             fleet_party_id, package_labor_hours, active, version, created_at, updated_at)
VALUES
    (md5('tier0:pkg:TIRE-INSTALL-PKG-4')::uuid, 'TIRE-INSTALL-PKG-4',
     'Four Tire Installation Package',
     'Mount and balance four tires, reset TPMS sensors and torque to specification. Priced as one job because the wheels come off once.',
     'PLATFORM', NULL, NULL, 1.6, true, 0, NOW(), NOW()),
    (md5('tier0:pkg:TIRE-INSTALL-PKG-4-PREMIUM')::uuid, 'TIRE-INSTALL-PKG-4-PREMIUM',
     'Four Tire Installation Package - Road Force',
     'The four-tire installation with road force variation balancing and nitrogen inflation in place of the standard balance.',
     'PLATFORM', NULL, NULL, 2.1, true, 0, NOW(), NOW()),
    (md5('tier0:pkg:SEASONAL-CHANGEOVER')::uuid, 'SEASONAL-CHANGEOVER',
     'Seasonal Tire Changeover',
     'Swap to the customer''s stored seasonal set: rotate on, balance, reset TPMS, record tread depths on the set coming off.',
     'PLATFORM', NULL, NULL, 1.4, true, 0, NOW(), NOW()),
    (md5('tier0:pkg:FLEET-PM-A-PKG')::uuid, 'FLEET-PM-A-PKG',
     'Fleet PM - A Service Package',
     'The A-interval preventive maintenance sold as one job, with the tread-depth audit the fleet log expects.',
     'PLATFORM', NULL, NULL, 1.7, true, 0, NOW(), NOW()),
    -- A fleet requirement set, not an offering: this account gets these operations every visit.
    (md5('tier0:pkg:FLEET-REQ-MERIDIAN')::uuid, 'FLEET-REQ-MERIDIAN',
     'Meridian Logistics - Standing Requirements',
     'Contracted requirements for every Meridian Logistics unit that enters a bay, regardless of the reason for the visit.',
     'PLATFORM', NULL, '0198f2a1-0000-7000-8000-0000000000f1'::uuid, 2.3, true, 0, NOW(), NOW())
ON CONFLICT (package_code) DO NOTHING;

INSERT INTO service_package_member (id, package_id, service_id, sequence, quantity, required, created_at, updated_at)
SELECT md5('tier0:pkgmem:' || v.package_code || ':' || v.operation_code)::uuid,
       p.id, s.id, v.sequence, v.quantity, v.required, NOW(), NOW()
FROM (VALUES
    -- Standard four-tire install: the balance and TPMS reset are part of the job, nitrogen is an upsell.
    ('TIRE-INSTALL-PKG-4',         'TIRE-INSTALL-SET-4',       10, 1.00, true),
    ('TIRE-INSTALL-PKG-4',         'WHEEL-BALANCE-SET-4',      20, 1.00, true),
    ('TIRE-INSTALL-PKG-4',         'TPMS-SENSOR-SERVICE',      30, 1.00, true),
    ('TIRE-INSTALL-PKG-4',         'LUG-TORQUE-RECHECK',       40, 1.00, true),
    ('TIRE-INSTALL-PKG-4',         'NITROGEN-FILL-SET-4',      50, 1.00, false),
    -- Premium variant: road force in place of the standard balance, nitrogen included rather than offered.
    ('TIRE-INSTALL-PKG-4-PREMIUM', 'TIRE-INSTALL-SET-4',       10, 1.00, true),
    ('TIRE-INSTALL-PKG-4-PREMIUM', 'ROAD-FORCE-BALANCE-SET-4', 20, 1.00, true),
    ('TIRE-INSTALL-PKG-4-PREMIUM', 'TPMS-SENSOR-SERVICE',      30, 1.00, true),
    ('TIRE-INSTALL-PKG-4-PREMIUM', 'NITROGEN-FILL-SET-4',      40, 1.00, true),
    ('TIRE-INSTALL-PKG-4-PREMIUM', 'LUG-TORQUE-RECHECK',       50, 1.00, true),
    -- Seasonal changeover.
    ('SEASONAL-CHANGEOVER',        'TIRE-ROTATION',            10, 1.00, true),
    ('SEASONAL-CHANGEOVER',        'WHEEL-BALANCE-SET-4',      20, 1.00, true),
    ('SEASONAL-CHANGEOVER',        'TPMS-SENSOR-SERVICE',      30, 1.00, true),
    ('SEASONAL-CHANGEOVER',        'FLEET-TREAD-DEPTH-AUDIT',  40, 1.00, true),
    -- Fleet PM A sold as a package.
    ('FLEET-PM-A-PKG',             'FLEET-PM-A-SERVICE',       10, 1.00, true),
    ('FLEET-PM-A-PKG',             'FLEET-TREAD-DEPTH-AUDIT',  20, 1.00, true),
    ('FLEET-PM-A-PKG',             'TIRE-ROTATION',            30, 1.00, false),
    -- Meridian's standing requirements: every member required, which is what makes it a requirement set.
    ('FLEET-REQ-MERIDIAN',         'DOT-ANNUAL-INSPECTION',    10, 1.00, true),
    ('FLEET-REQ-MERIDIAN',         'FLEET-TREAD-DEPTH-AUDIT',  20, 1.00, true),
    ('FLEET-REQ-MERIDIAN',         'LUG-TORQUE-RECHECK',       30, 1.00, true),
    ('FLEET-REQ-MERIDIAN',         'MICHELIN-CASING-INSPECTION', 40, 1.00, true)
) AS v (package_code, operation_code, sequence, quantity, required)
JOIN service_package p ON p.package_code = v.package_code
JOIN service s ON s.operation_code = v.operation_code
ON CONFLICT (package_id, service_id) DO NOTHING;
