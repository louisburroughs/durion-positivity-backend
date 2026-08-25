-- #1499 / #1512: resolve the workorder:start vs workorder:workorder:start split-brain,
-- per docs/rbac-permission-role-audit-2026-08.md §2 finding 1 / §7 task 2.
--
-- The start-workorder endpoint (OperationalContextController) enforced
-- workorder:start (bit 284), which TECHNICIAN, LOCATION_MANAGER and ADMIN held.
-- The detail-response capability flag (WorkorderDetailServiceImpl) checked
-- workorder:workorder:start (bit 180), which only ADMIN held -- so a technician
-- could start a workorder while the UI reported they couldn't. Both call sites
-- now enforce workorder:workorder:start (the domain:resource:action-conformant
-- name); workorder:start retires.
--
-- Grants move rather than merely add: TECHNICIAN and LOCATION_MANAGER gain
-- workorder:workorder:start (name-resolved, idempotent), then every
-- workorder:start grant row is deleted -- ADMIN included, since ADMIN already
-- holds workorder:workorder:start via R__seed_role_permissions.sql.
--
-- The workorder:start permission definition and its bit index (284) are NOT
-- removed -- bit indexes are permanent (PermissionCode javadoc) and the repo's
-- retirement convention (§4 of the audit doc) only ever retires grants, never
-- the catalog entry.

-- ---------------------------------------------------------------------------
-- 1. Grant workorder:workorder:start to TECHNICIAN and LOCATION_MANAGER.
-- ---------------------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    ('LOCATION_MANAGER', 'workorder:workorder:start'),
    ('TECHNICIAN', 'workorder:workorder:start')
) AS g(role_name, permission_name)
JOIN roles r ON r.name = g.role_name
JOIN permissions p ON p.name = g.permission_name
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Retire every workorder:start grant (ADMIN, LOCATION_MANAGER, TECHNICIAN).
-- ---------------------------------------------------------------------------
DELETE FROM role_permissions
WHERE permission_id IN (
    SELECT id FROM permissions WHERE name = 'workorder:start'
);
