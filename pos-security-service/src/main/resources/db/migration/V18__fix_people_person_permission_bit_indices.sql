-- PERM-people-person: Correct bit_index values for people:person:* permissions.
-- The seed assigned these bits 221-224, which collide with nlti and mcp permissions
-- added later. GatewayPermissionCatalog places them at bits 233-236.
UPDATE permissions SET bit_index = 233 WHERE name = 'people:person:view';
UPDATE permissions SET bit_index = 234 WHERE name = 'people:person:create';
UPDATE permissions SET bit_index = 235 WHERE name = 'people:person:edit';
UPDATE permissions SET bit_index = 236 WHERE name = 'people:person:delete';
