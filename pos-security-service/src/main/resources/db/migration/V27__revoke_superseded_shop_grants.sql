-- Re-point superseded shop:location/shop:bay grants to the live location:*
-- family, per docs/rbac-permission-role-audit-2026-08.md §3 / §7 task 3.
--
-- pos-shop-manager's contract enforces none of shop:location:view/create/edit/
-- deactivate or shop:bay:view/create/edit; pos-location enforces the live
-- location:read/write and location:bay:read/manage family instead. This is a
-- faithful mirror of who held what -- no widening:
--   shop:location:view / shop:bay:view (ADMIN, DISPATCHER, LOCATION_MANAGER,
--     SERVICE_ADVISOR, SHOP_MANAGER)   -> location:read / location:bay:read
--   shop:location:create+edit (ADMIN, LOCATION_MANAGER)
--     -> location:write (LOCATION_MANAGER only; ADMIN already holds it)
--   shop:location:deactivate (ADMIN only) -> covered by ADMIN's existing
--     location:write; nothing to add
--   shop:bay:create+edit (ADMIN, LOCATION_MANAGER)
--     -> location:bay:manage (LOCATION_MANAGER only; ADMIN already holds it)
--
-- Live pos-shop-manager codes -- shop:bay:assign, shop:schedule:*,
-- shop:technician:view -- are untouched.
--
-- Grants move rather than merely add: the location:* rows above are inserted
-- (name-resolved, idempotent) and then every row for the seven dead shop:*
-- codes is deleted, for every role that held one. The permission definition
-- rows for the seven dead codes are NOT removed -- bit indexes are permanent;
-- only grants retire.

-- ---------------------------------------------------------------------------
-- 1. Grant the missing location:* rows, mirroring each role's dead shop:*
--    holdings. ADMIN already holds the full location:* family and is omitted.
-- ---------------------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    ('DISPATCHER', 'location:bay:read'),
    ('DISPATCHER', 'location:read'),
    ('LOCATION_MANAGER', 'location:bay:manage'),
    ('LOCATION_MANAGER', 'location:bay:read'),
    ('LOCATION_MANAGER', 'location:read'),
    ('LOCATION_MANAGER', 'location:write'),
    ('SERVICE_ADVISOR', 'location:bay:read'),
    ('SERVICE_ADVISOR', 'location:read'),
    ('SHOP_MANAGER', 'location:bay:read'),
    ('SHOP_MANAGER', 'location:read')
) AS g(role_name, permission_name)
JOIN roles r ON r.name = g.role_name
JOIN permissions p ON p.name = g.permission_name
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Retire the seven dead shop:* grants from every role that holds one.
-- ---------------------------------------------------------------------------
DELETE FROM role_permissions
WHERE permission_id IN (
    SELECT id FROM permissions WHERE name IN (
        'shop:location:view',
        'shop:location:create',
        'shop:location:edit',
        'shop:location:deactivate',
        'shop:bay:view',
        'shop:bay:create',
        'shop:bay:edit'
    )
);
