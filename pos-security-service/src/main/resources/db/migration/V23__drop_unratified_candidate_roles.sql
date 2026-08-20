-- #1373: delete the two unratified "Candidate Roles v0" seeded by V3.
--
-- V3__seed_candidate_roles.sql created READ_ONLY_SCHEDULER, DISPATCHER,
-- SHOP_MANAGER and SECURITY_ADMIN under a header calling them "Candidate Roles
-- v0". DISPATCHER and SHOP_MANAGER were subsequently ratified and carry real
-- grants in R__seed_role_permissions.sql. The other two never were: nothing in
-- the codebase references SECURITY_ADMIN or READ_ONLY_SCHEDULER — no controller,
-- no test, no configuration — and SECURITY_ADMIN's described scope ("manage
-- roles, permissions, and user role assignments") is already held by
-- SYSTEM_ADMINISTRATOR. Rather than leave two assignable roles that can do
-- nothing but reach the assistant, remove them.
--
-- V3 is already applied everywhere, so it is left untouched (editing it would
-- change its checksum and fail validation). This is the forward correction: on a
-- fresh database V3 inserts the roles and this migration removes them again.
--
-- Deleting the roles rather than only their grants is deliberate. An empty role
-- that still exists is assignable, and an operator granting to it through the
-- role-permission admin API would quietly resurrect the capability question
-- #1373 closed.

-- Drop dependents first: roles(id) is referenced by role_permissions,
-- user_roles, role_assignments and principal_roles.
DELETE FROM role_assignment_scope_locations
WHERE role_assignment_id IN (
    SELECT ra.id
    FROM role_assignments ra
    JOIN roles r ON r.id = ra.role_id
    WHERE r.name IN ('SECURITY_ADMIN', 'READ_ONLY_SCHEDULER')
);

DELETE FROM role_assignments
WHERE role_id IN (SELECT id FROM roles WHERE name IN ('SECURITY_ADMIN', 'READ_ONLY_SCHEDULER'));

DELETE FROM user_roles
WHERE role_id IN (SELECT id FROM roles WHERE name IN ('SECURITY_ADMIN', 'READ_ONLY_SCHEDULER'));

DELETE FROM principal_roles
WHERE role_id IN (SELECT id FROM roles WHERE name IN ('SECURITY_ADMIN', 'READ_ONLY_SCHEDULER'));

DELETE FROM role_permissions
WHERE role_id IN (SELECT id FROM roles WHERE name IN ('SECURITY_ADMIN', 'READ_ONLY_SCHEDULER'));

DELETE FROM roles WHERE name IN ('SECURITY_ADMIN', 'READ_ONLY_SCHEDULER');
