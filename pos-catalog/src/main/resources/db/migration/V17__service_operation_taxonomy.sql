-- Operation taxonomy on the service master record (#1569 scope item 2, sourcing plan §4.2).
--
-- ServiceEntity is the system of record for estimated service time (decision recorded on
-- #1569, 2026-08-29), but the service table carried only naming: no stable operation
-- identity for vendor labor-guide codes to map onto, and no time field at all.
--
--   operation_code      Durion-owned operation identity (e.g. BRAKE-PAD-FRONT). Vendor
--                       codes map onto ours, never the reverse. Unique when present;
--                       nullable because dealer-created one-off services may never join
--                       a guide taxonomy.
--   operation_category  REPAIR | DIAGNOSTIC | MAINTENANCE | TIRE_SERVICE. Coarse class
--                       used later by source-precedence policy (tire ops prefer
--                       manufacturer install times) and diagnostic-block handling.
--   default_labor_hours Vehicle-agnostic fallback ONLY, decimal hours in tenths per
--                       industry flat-rate convention. Deliberately second-class: the
--                       vehicle-keyed service_labor_standard rows (V18) are the real
--                       answer; this is what a single-scalar shop can author by hand and
--                       what may later ride the catalog service fact as a degraded-mode
--                       default. It never claims to be vehicle-correct.

ALTER TABLE service ADD COLUMN operation_code varchar(64);
ALTER TABLE service ADD COLUMN operation_category varchar(32);
ALTER TABLE service ADD COLUMN default_labor_hours numeric(5,1);

CREATE UNIQUE INDEX ux_service_operation_code ON service (operation_code)
    WHERE operation_code IS NOT NULL;
