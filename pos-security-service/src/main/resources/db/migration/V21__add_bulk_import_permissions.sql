-- PERM-bulk-import: Assign bit_index values for bulk import permissions
-- introduced in PermissionCode catalog version 6.
-- These rows are registered at startup by pos-bulk-loader PermissionRegistration.
-- Bulk Import — bit indexes 239–240
UPDATE permissions
SET bit_index = 239
WHERE name = 'bulkImport:upload:execute';
UPDATE permissions
SET bit_index = 240
WHERE name = 'bulkImport:status:read';
