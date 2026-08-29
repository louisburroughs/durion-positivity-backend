-- Retire the grants for pos-workorder's time_entry approval codes (#1564).
--
-- The two endpoints these guarded are gone: pos-workorder's time_entry table had no
-- writer in any production path, so approveTimeEntry/rejectTimeEntry could only ever
-- answer 404. Employee time entries belong to pos-people, whose clock surface writes
-- them and whose people:timeEntry:approve / people:timeEntry:reject codes guard the
-- approval that actually reaches a row.
--
-- Grants only, per the §4 retirement convention in
-- docs/rbac-permission-role-audit-2026-08.md. Deliberately untouched:
--   * the permission-definition rows in R__seed_role_permissions.sql -- section 4 of
--     that file asserts every listed name resolves, and bit indexes are permanent;
--   * PermissionCode bits 343 and 344, now marked @Deprecated -- never removed or
--     renumbered, so CATALOG_VERSION does not move and issued JWTs stay valid;
--   * the pos-workorder permissions.yaml entries, now carrying deprecated: true, which
--     the registration chain propagates to the permissions table at service startup.
--
-- Old JWTs still carrying bits 343/344 lose nothing: no endpoint enforces either code.
--
-- V25 and V28 are the precedent for a versioned, name-resolved, grant-only revoke
-- against the additive repeatable seed (ON CONFLICT DO NOTHING never deletes).

DELETE FROM role_permissions
WHERE permission_id IN (
    SELECT id FROM permissions WHERE name IN (
        'workorder:timeEntry:approve',
        'workorder:timeEntry:reject'
    )
);
