-- CAP-325 D14.1 / DECISION-LOCATION-025: the specialty map, derived from tier0-services.csv.
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
-- Seeded under TWO tenants, in one transaction (pattern: pos-security-service's
-- R__seed_tenant_template.sql — a temp table holds the shared rows, then each tenant binding
-- writes its own copy):
--
--   1. The alpha default tenant (`...0001`, `pos.tenancy.default-tenant-id`), seeded since #1668
--      and read by every alpha shop today. Its ids are md5('bso:'||bay_type||':'||operation_code)
--      — no tenant in the hash, an artifact of this table having launched single-tenant — and stay
--      exactly as originally seeded: this file must never change an already-applied row's identity.
--
--   2. The platform tenant (`PlatformTenant.ID`, `...0000`), added by DECISION-LOCATION-025. This
--      is the provisioning template `BaySpecialtyMapProvisioningService` copies into every newly
--      created tenant on `tenant.created` — it is not, and has never been, an alpha-shop row.
--      Before this addition the platform tenant had no rows at all, so provisioning had nothing to
--      copy and every new tenant's map was silently empty. Its ids are tenant-qualified
--      (md5('bso:'||tenant||':'||bay_type||':'||operation_code)) so they can never collide with
--      the alpha rows above: the table's primary key is `id` alone (V4), not (tenant_id, id), so
--      two tenants' rows sharing an id would violate it.
--
-- Ids are md5-derived from the natural key so reruns are deterministic; ON CONFLICT keeps the
-- repeatable migration idempotent when its checksum changes.
SET TIME ZONE 'UTC';

DO $$
DECLARE
    alpha    CONSTANT uuid := '01900000-0000-7000-8000-000000000001';
    platform CONSTANT uuid := '01900000-0000-7000-8000-000000000000';
BEGIN
    CREATE TEMP TABLE bay_specialty_template (bay_type text, operation_code text) ON COMMIT DROP;
    INSERT INTO bay_specialty_template (bay_type, operation_code) VALUES
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
        ('INSPECTION',   'DOT-ANNUAL-INSPECTION');

    -- 1. Alpha default tenant: unchanged ids, unchanged rows.
    PERFORM set_config('app.current_tenant', alpha::text, true);

    INSERT INTO bay_specialty_operation (id, bay_type, operation_code, created_at, updated_at)
    SELECT md5('bso:' || t.bay_type || ':' || t.operation_code)::uuid, t.bay_type, t.operation_code, NOW(), NOW()
    FROM bay_specialty_template t
    ON CONFLICT (tenant_id, bay_type, operation_code) DO NOTHING;

    -- 2. Platform tenant: the provisioning template (DECISION-LOCATION-025). Same rows,
    -- tenant-qualified ids so they cannot collide with the alpha ones above.
    PERFORM set_config('app.current_tenant', platform::text, true);

    INSERT INTO bay_specialty_operation (id, bay_type, operation_code, created_at, updated_at)
    SELECT md5('bso:' || platform::text || ':' || t.bay_type || ':' || t.operation_code)::uuid,
           t.bay_type, t.operation_code, NOW(), NOW()
    FROM bay_specialty_template t
    ON CONFLICT (tenant_id, bay_type, operation_code) DO NOTHING;
END $$;
