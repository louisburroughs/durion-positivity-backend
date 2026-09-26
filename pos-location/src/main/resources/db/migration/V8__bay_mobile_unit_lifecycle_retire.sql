-- #2264, DECISION-LOCATION-026: bays and mobile units share one lifecycle status set,
-- ACTIVE | OUT_OF_SERVICE | RETIRED, each enforced by a database CHECK. DELETE no longer
-- hard-deletes either aggregate; it retires (BayServiceImpl.deleteBay,
-- MobileUnitServiceImpl.deleteMobileUnit) — see BayUpdatedV1/MobileUnitUpdatedV1, which now carry
-- status and the three out-of-service fields, and BayDeletedV1/MobileUnitDeletedV1, which
-- pos-location no longer emits.
--
-- bays.status has never had a CHECK. mobile_units.status has carried mobile_units_status_check
-- (ACTIVE, INACTIVE) since V6; INACTIVE is retired outright (pre-production, no compatibility
-- shim), so every mobile unit at INACTIVE becomes OUT_OF_SERVICE with reason OTHER and note
-- 'migrated from INACTIVE' before the new CHECK lands.
--
-- Both tables are under FORCE ROW LEVEL SECURITY (V1 baseline) and Flyway connects as the owner,
-- which carries a transitional default tenant binding (postgres/init-tenancy.sh). Without lifting
-- FORCE, the data migration and the CHECK validation scan (which validates the whole heap) would
-- only see that one tenant's rows, silently leaving every other tenant's mobile units on
-- INACTIVE and letting the CHECK land unvalidated against them. FORCE is dropped for this
-- transaction on both tables and restored before commit — the pos-location V6 / pos-shop-manager
-- V11 pattern.
ALTER TABLE public.bays NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.mobile_units NO FORCE ROW LEVEL SECURITY;

ALTER TABLE public.bays ADD COLUMN out_of_service_reason varchar(32);
ALTER TABLE public.bays ADD COLUMN out_of_service_note varchar(255);
ALTER TABLE public.bays ADD COLUMN expected_return_at timestamptz;
ALTER TABLE public.bays ADD COLUMN display_order integer;

ALTER TABLE public.mobile_units ADD COLUMN out_of_service_reason varchar(32);
ALTER TABLE public.mobile_units ADD COLUMN out_of_service_note varchar(255);
ALTER TABLE public.mobile_units ADD COLUMN expected_return_at timestamptz;

UPDATE public.mobile_units
   SET status = 'OUT_OF_SERVICE',
       out_of_service_reason = 'OTHER',
       out_of_service_note = 'migrated from INACTIVE'
 WHERE status = 'INACTIVE';

ALTER TABLE public.mobile_units DROP CONSTRAINT mobile_units_status_check;

ALTER TABLE public.bays
    ADD CONSTRAINT bays_status_check
    CHECK (status IN ('ACTIVE', 'OUT_OF_SERVICE', 'RETIRED'));

ALTER TABLE public.mobile_units
    ADD CONSTRAINT mobile_units_status_check
    CHECK (status IN ('ACTIVE', 'OUT_OF_SERVICE', 'RETIRED'));

ALTER TABLE public.bays
    ADD CONSTRAINT bays_out_of_service_reason_check
    CHECK (out_of_service_reason IS NULL OR out_of_service_reason IN
        ('EQUIPMENT_FAILURE', 'SCHEDULED_MAINTENANCE', 'INSPECTION', 'SAFETY_HOLD', 'FACILITY_ISSUE', 'OTHER'));

ALTER TABLE public.mobile_units
    ADD CONSTRAINT mobile_units_out_of_service_reason_check
    CHECK (out_of_service_reason IS NULL OR out_of_service_reason IN
        ('EQUIPMENT_FAILURE', 'SCHEDULED_MAINTENANCE', 'INSPECTION', 'SAFETY_HOLD', 'FACILITY_ISSUE', 'OTHER'));

ALTER TABLE public.bays FORCE ROW LEVEL SECURITY;
ALTER TABLE public.mobile_units FORCE ROW LEVEL SECURITY;
