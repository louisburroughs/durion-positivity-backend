-- V21: invoice:finalize:override — manager elevation authority for invoice finalization.
--
-- Adds the permanent bit_index for the new permission (catalog version 15) and grants
-- it to the manager/admin roles that may approve elevated finalization, either by
-- holding it directly (logged-in manager) or by acting as the named approver in the
-- manager-approval-by-employee-number flow.
--
-- RULE: bit_index values are permanent. Never reassign or reuse them.
-- The permission row itself is registered at startup by pos-invoice PermissionRegistration
-- (permissions.yaml); this migration assigns its bit_index and seeds role grants.

-- ── Bit index (catalog version 15, bit index 346) ────────────────────────────
UPDATE permissions
SET bit_index = 346
WHERE name = 'invoice:finalize:override';

-- ── Role grants ──────────────────────────────────────────────────────────────
-- Grant invoice:finalize:override to SHOP_MANAGER, LOCATION_MANAGER, ADMIN.
-- Idempotent: skips roles/permission not yet present and existing grants.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.name = 'invoice:finalize:override'
  AND r.name IN ('SHOP_MANAGER', 'LOCATION_MANAGER', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;
