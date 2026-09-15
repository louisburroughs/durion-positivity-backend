-- Workorder vehicle_id from crm_vehicle_id.
--
-- Workorders created from an estimate copied only crm_vehicle_id and left vehicle_id NULL. The
-- workorder detail read, the shop dashboard's vehicle registry lookup and the dispatch board's
-- vehicle description all key on vehicle_id, so every such workorder showed "Vehicle details
-- unavailable". crm_vehicle_id carries the pos-vehicle-inventory vehicle id (it matches
-- ext_vehicle.vehicle_id), so it is copied across where vehicle_id is missing. A value that is not
-- UUID-shaped names nothing in the registry and is left alone, the same rule the application now
-- applies on creation.
UPDATE public.workorder
SET vehicle_id = crm_vehicle_id::uuid
WHERE vehicle_id IS NULL
  AND crm_vehicle_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
