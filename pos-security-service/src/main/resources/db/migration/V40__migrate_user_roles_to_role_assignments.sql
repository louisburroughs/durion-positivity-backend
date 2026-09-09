-- ADR-0061 amendment (2026-09-09, #1914) phase 2: role_assignments becomes the only store of a
-- user's roles. A role is a bundle that delivers permission grants; a user holds one only through
-- an effective-dated assignment. This migrates every remaining user_roles row into an open-ended
-- role_assignments row and drops user_roles.
--
-- Safe because issuance already unions both stores (EffectiveGrantResolver, #1914 phase 1): a
-- user_roles grant and an equivalent open-ended role_assignments row resolve to the identical
-- permission set at every decision point (CustomUserDetailsService, UserServiceImpl, Authorization-
-- ServiceImpl.authorizePerson, RoleManagementServiceImpl.userHasPermission / getUserPermissions).
-- This migration only makes that grant visible to the assignment-listing / People access view
-- (RoleManagementService.getAssignmentsForUser / getEffectiveRoleAssignments) and revocable,
-- neither of which a bare user_roles row ever was.
--
-- effective_start_date is taken from the owning user's created_at (already TIMESTAMP WITH TIME
-- ZONE / UTC, same as role_assignments' own effective_* columns since V5), so a migrated
-- assignment reads as having existed for as long as the account did rather than starting at
-- migration time. effective_end_date, last_modified_at, last_modified_by and revoked_at are left
-- NULL: an open-ended, never-revoked assignment, matching what the user_roles row itself asserted
-- (unconditional membership, no window, never revoked).
--
-- A (user_id, role_id) pair that already has a role_assignments row effective right now is
-- skipped, using the same half-open window RoleAssignmentRepository.findEffectiveAssignmentsByUser
-- / RoleAssignment.isEffectiveAt define (effective_start_date <= now() AND (effective_end_date IS
-- NULL OR effective_end_date > now())): a user granted the same role through both stores before
-- this phase does not end up with two overlapping open-ended assignments for it.
--
-- id: gen_random_uuid() rather than a UUID v7 helper. UUID v7 generation in this codebase is a
-- Hibernate-side @PrePersist concern (UUIDv7Id / UUIDv7Generator; docs/UUID_V7_MIGRATION.md) with
-- no SQL-side equivalent; every earlier migration or seed that mints its own row ids in this
-- module (V3, V8, R__seed_role_permissions.sql) uses gen_random_uuid() for the same reason.
INSERT INTO role_assignments (
    id,
    user_id,
    role_id,
    effective_start_date,
    effective_end_date,
    created_at,
    created_by,
    last_modified_at,
    last_modified_by,
    revoked_at
)
SELECT
    gen_random_uuid(),
    ur.user_id,
    ur.role_id,
    u.created_at,
    NULL,
    NOW(),
    'V40__migrate_user_roles_to_role_assignments',
    NULL,
    NULL,
    NULL
FROM user_roles ur
JOIN users u ON u.id = ur.user_id
WHERE NOT EXISTS (
    SELECT 1
    FROM role_assignments ra
    WHERE ra.user_id = ur.user_id
      AND ra.role_id = ur.role_id
      AND ra.effective_start_date <= NOW()
      AND (ra.effective_end_date IS NULL OR ra.effective_end_date > NOW())
);

DROP TABLE user_roles;
