-- #1563: inventory:cycle_count:initiate/view/complete were granted to ADMIN
-- alone -- every inventory role (INVENTORY_LEAD, INVENTORY_MANAGER,
-- INVENTORY_CONTROLLER) held only inventory:cycle_count_tolerance:manage,
-- which configures tolerances but cannot touch a count. The clerk who
-- physically counts stock could not plan, record or even read a cycle count.
--
-- R__seed_role_permissions.sql now grants all three permissions to
-- INVENTORY_MANAGER, INVENTORY_CONTROLLER, INVENTORY_LEAD and
-- LOCATION_MANAGER in the same change, so a fresh database matches. That
-- seed is additive only (ON CONFLICT DO NOTHING, never deletes -- see its own
-- IDEMPOTENCY note), so it cannot correct an already-populated database.
-- This versioned migration is what reaches one, mirroring the V25/V26
-- precedent for a deliberate, forward-only grant.
--
-- Purely additive: no permission is revoked from anyone, ADMIN included.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    ('INVENTORY_CONTROLLER', 'inventory:cycle_count:complete'),
    ('INVENTORY_CONTROLLER', 'inventory:cycle_count:initiate'),
    ('INVENTORY_CONTROLLER', 'inventory:cycle_count:view'),
    ('INVENTORY_LEAD', 'inventory:cycle_count:complete'),
    ('INVENTORY_LEAD', 'inventory:cycle_count:initiate'),
    ('INVENTORY_LEAD', 'inventory:cycle_count:view'),
    ('INVENTORY_MANAGER', 'inventory:cycle_count:complete'),
    ('INVENTORY_MANAGER', 'inventory:cycle_count:initiate'),
    ('INVENTORY_MANAGER', 'inventory:cycle_count:view'),
    ('LOCATION_MANAGER', 'inventory:cycle_count:complete'),
    ('LOCATION_MANAGER', 'inventory:cycle_count:initiate'),
    ('LOCATION_MANAGER', 'inventory:cycle_count:view')
) AS g(role_name, permission_name)
JOIN roles r ON r.name = g.role_name
JOIN permissions p ON p.name = g.permission_name
ON CONFLICT DO NOTHING;
