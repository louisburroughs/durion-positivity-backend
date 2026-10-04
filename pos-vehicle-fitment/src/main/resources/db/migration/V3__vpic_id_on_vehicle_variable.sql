-- #2454: keep vPIC's own numeric id on the cached vehicle variable rows.
--
-- GetVehicleVariableValuesList/{id} takes vPIC's variable id (the ID of GetVehicleVariableList), not the local
-- UUID. NULL on rows cached before this column existed or not from vPIC: they are served from cache until the
-- next variable refresh adopts them by name.
--
-- Tenancy: vehicle_variable is a global reference table (db/tenancy-global-tables.txt), so the new column
-- carries no tenant_id and no policy change.

ALTER TABLE public.vehicle_variable ADD COLUMN nhtsa_id bigint;
