-- #1512: give role_permissions a provenance trail.
--
-- The table was created by V1 as a bare join table -- (role_id, permission_id)
-- as the primary key and two foreign keys, nothing else. Every other RBAC table
-- carries audit columns: roles and role_assignments have created_at/created_by,
-- permissions has registered_at/registered_by_service. The one table that
-- records actual authority had no record of who granted it or when.
--
-- That gap is what made the alpha investigation in #1512 expensive. An
-- out-of-band grant gave SYSTEM_ADMINISTRATOR 398 permissions -- every row then
-- in the permissions table, against a seed that grants it 40. Establishing that
-- much took cross-referencing the Flyway history, per-service registration
-- timestamps and the catalog bit indexes, and even then the actor is still
-- unknown, because the database never recorded one. With these columns the same
-- question is a single SELECT.
--
-- Column semantics:
--
--   granted_at  NULL means the row predates this migration. Existing rows are
--               deliberately left NULL rather than stamped with NOW(): their
--               real grant time is unknown and inventing one would defeat the
--               purpose of an audit column. The DEFAULT is attached after the
--               backfill precisely so that ADD COLUMN does not apply it to
--               existing rows.
--
--   granted_by  NULL means the row was written by something other than the
--               role-permission admin API -- the Flyway seeds, a psql session,
--               or an out-of-band script. That is a signal, not a defect: a
--               grant nobody can be named for is exactly the case #1512 is
--               about. Rows that existed before this migration are marked
--               'pre-v30-unattributed' to keep them distinguishable from
--               unattributed rows written after it.
--
-- Both columns are nullable and neither is written by Hibernate's @ManyToMany
-- mapping of Role.permissions, so an insert through the entity model continues
-- to work unchanged and picks up the granted_at default.

ALTER TABLE role_permissions
    ADD COLUMN IF NOT EXISTS granted_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS granted_by VARCHAR(255);

-- Mark pre-existing grants as unattributed. Runs before the DEFAULT is set, so
-- granted_at stays NULL for them.
UPDATE role_permissions
SET granted_by = 'pre-v30-unattributed'
WHERE granted_by IS NULL;

ALTER TABLE role_permissions
    ALTER COLUMN granted_at SET DEFAULT NOW();

COMMENT ON COLUMN role_permissions.granted_at IS
    'When the grant was written. NULL for rows predating V30.';

COMMENT ON COLUMN role_permissions.granted_by IS
    'Actor that granted the permission via the admin API. NULL when written by a seed, direct SQL, or an out-of-band script; ''pre-v30-unattributed'' for rows predating V30.';
