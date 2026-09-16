-- Tenant binding for the seed rows below (ADR-0062); transaction-local.
SELECT set_config('app.current_tenant', '01900000-0000-7000-8000-000000000001', true);

-- CAP-325 D14.1: the platform default specialty map, derived from tier0-services.csv.
--
-- "Specialty" means the work cannot be done without equipment specific to that bay type — not
-- merely that it is customarily done there. So TIRE-ROTATION and LUG-TORQUE-RECHECK (lift and
-- torque wrench) and the three tire inspections (gauge and eye) are general, and the ranking rule
-- lets a tire shop still do them in the tire bay by habit. TRANSMISSION-SERVICE needs a
-- fluid-exchange machine but no bay type exists for it; inventing one for a single service is
-- worse than treating it as general.
--
-- HEAVY_DUTY has no rows: it is a max_duty_class ceiling (D13), not an equipment set.
-- WASH_DETAIL has no rows because the catalog seeds no wash services — and it is excluded from
-- the general default in code, so it never absorbs mechanical work.
-- GENERAL_SERVICE has no rows by definition.
--
-- Ids are md5-derived from the natural key so reruns are deterministic; ON CONFLICT keeps the
-- repeatable migration idempotent when its checksum changes.
SET TIME ZONE 'UTC';

INSERT INTO bay_specialty_operation (id, bay_type, operation_code, created_at, updated_at)
SELECT md5('bso:' || m.bay_type || ':' || m.operation_code)::uuid, m.bay_type, m.operation_code, NOW(), NOW()
FROM (VALUES
    ('ALIGNMENT',    'WHEEL-ALIGNMENT-4-WHEEL'),
    ('TIRE_SERVICE', 'TIRE-INSTALL-SET-4'),
    ('TIRE_SERVICE', 'TIRE-INSTALL-LT-SET-4'),
    ('TIRE_SERVICE', 'TIRE-INSTALL-COMMERCIAL-SINGLE'),
    ('TIRE_SERVICE', 'TIRE-REPAIR-PATCH-PLUG'),
    ('TIRE_SERVICE', 'WHEEL-BALANCE-SET-4'),
    ('TIRE_SERVICE', 'ROAD-FORCE-BALANCE-SET-4'),
    ('TIRE_SERVICE', 'NITROGEN-FILL-SET-4'),
    ('TIRE_SERVICE', 'TPMS-SENSOR-SERVICE'),
    ('TIRE_SERVICE', 'TPMS-SENSOR-REPLACE-SINGLE'),
    ('INSPECTION',   'DOT-ANNUAL-INSPECTION')
) AS m (bay_type, operation_code)
ON CONFLICT (tenant_id, bay_type, operation_code) DO NOTHING;
