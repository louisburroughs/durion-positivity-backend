-- #1656: type discriminator for workorder.resource_id.
--
-- resource_id has been a bare UUID since CAP:140 #64, with no way to say whether it points at a
-- pos-location bay or a pos-location mobile unit — two aggregates with separate identity, separate
-- lifecycle and no shared table. The dispatch board consequently treated every assigned resource as
-- a bay, so mobile work was invisible.
--
-- Nullable rather than NOT NULL because the upstream pos-shop-manager assignment publisher does not
-- emit the field yet; inbound events resolve an absent value to BAY (ResourceType.orDefault), which
-- is exactly what every pre-#1656 assignment meant. The backfill below applies that same rule to
-- rows already in the table, so no existing assignment changes meaning. Rows with no resource_id
-- stay NULL: an unassigned workorder has no resource to type.
ALTER TABLE workorder ADD COLUMN resource_type varchar(32);

ALTER TABLE workorder ADD CONSTRAINT workorder_resource_type_check
    CHECK (resource_type IS NULL OR resource_type IN ('BAY', 'MOBILE_UNIT'));

UPDATE workorder SET resource_type = 'BAY' WHERE resource_id IS NOT NULL;
