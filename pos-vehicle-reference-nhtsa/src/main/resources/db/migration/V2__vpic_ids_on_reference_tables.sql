-- #2454: keep vPIC's own numeric id on the cached reference rows.
--
-- vPIC resolves its dependent lookups by its own integers (GetMakeForManufacturer/{Mfr_ID},
-- GetModelsForMakeId/{Make_ID}, GetVehicleTypesForMakeId/{Make_ID}, GetVehicleVariableValuesList/{ID}); the local
-- UUID cannot stand in for them. NULL on rows cached before this column existed or not from vPIC: those are
-- served from cache until the next refresh of their parent list re-keys them.
--
-- Tenancy: all of these are global reference tables (db/tenancy-global-tables.txt), so the new columns carry no
-- tenant_id and no policy change.

ALTER TABLE public.manufacturer ADD COLUMN nhtsa_id bigint;
ALTER TABLE public.make ADD COLUMN nhtsa_id bigint;
ALTER TABLE public.model ADD COLUMN nhtsa_id bigint;
ALTER TABLE public.vehicle_variable ADD COLUMN nhtsa_id bigint;
