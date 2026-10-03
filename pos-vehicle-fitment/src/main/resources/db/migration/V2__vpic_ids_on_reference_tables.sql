-- #2416: keep vPIC's own numeric id on the cached reference rows.
--
-- vPIC identifies manufacturers, makes and models by integers (Mfr_ID, Make_ID, Model_ID) and resolves
-- its dependent lookups by them (GetMakeForManufacturer/{Mfr_ID}, GetModelsForMakeId/{Make_ID},
-- GetVehicleTypesForMakeId/{Make_ID}). The local UUID cannot stand in for that id, so it is stored here.
-- NULL on rows that did not come from vPIC (seeded, bulk-loaded, or created by a fitment request):
-- those rows have no vPIC parent to refresh from.
--
-- Tenancy: manufacturer, make and model are global reference tables (db/tenancy-global-tables.txt),
-- so the new columns carry no tenant_id and no policy change.

ALTER TABLE public.manufacturer ADD COLUMN nhtsa_id bigint;
ALTER TABLE public.make ADD COLUMN nhtsa_id bigint;
ALTER TABLE public.model ADD COLUMN nhtsa_id bigint;
