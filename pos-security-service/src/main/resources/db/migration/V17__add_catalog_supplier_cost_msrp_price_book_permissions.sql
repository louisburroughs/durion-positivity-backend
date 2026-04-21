-- PERM-catalog-batch2: Assign bit_index values for catalog supplier cost, MSRP,
-- and price book permissions introduced in PermissionCode catalog version 4.
-- These rows are registered at startup by pos-catalog PermissionRegistration.
-- Catalog (batch 2) — bit indexes 227–232
UPDATE permissions
SET bit_index = 227
WHERE name = 'catalog:supplier_cost:read';
UPDATE permissions
SET bit_index = 228
WHERE name = 'catalog:supplier_cost:write';
UPDATE permissions
SET bit_index = 229
WHERE name = 'catalog:msrp:read';
UPDATE permissions
SET bit_index = 230
WHERE name = 'catalog:msrp:write';
UPDATE permissions
SET bit_index = 231
WHERE name = 'catalog:price_book:read';
UPDATE permissions
SET bit_index = 232
WHERE name = 'catalog:price_book:write';