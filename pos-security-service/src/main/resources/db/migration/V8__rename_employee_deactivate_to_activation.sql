-- Rename the people:employee:deactivate permission to people:employee:activation.
--
-- The permission is keyed by id (a UUID), and role_permissions.permission_id references the
-- id column, not the name. Renaming the row in place via UPDATE preserves all existing grants
-- and role assignments without requiring any changes to role_permissions rows.
--
-- bit_index 119 remains unchanged. Changing the bit index would invalidate the permission
-- bitset in every already-issued JWT token, forcing a global token revocation and re-issue
-- cycle. By preserving it, existing tokens remain valid through the change.

UPDATE permissions
SET name = 'people:employee:activation',
    action = 'activation',
    description = 'Activate and deactivate employee records',
    updated_at = now()
WHERE name = 'people:employee:deactivate';
