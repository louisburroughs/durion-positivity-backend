-- ADR-0061 §1 (#1875): location scope is no longer a property of a role assignment.
--
-- role_assignments keeps effective-dated user -> role assignment (effective_start_date,
-- effective_end_date, revoked_at). Reach now lives on the role (roles.location_scope /
-- location_hierarchy, V37) combined with pos-people's staffing assignment replicated by V36.
--
-- Forward-only, no data migration: verified at execution time that no migration ever seeded a
-- role_assignments row with scope_type = 'LOCATION' (every seeded row is 'GLOBAL') and that
-- role_assignment_scope_locations was never populated (V1 creates it, V23 only deletes from it).
-- Dropping the column therefore loses nothing a reader could have observed.
DROP TABLE IF EXISTS role_assignment_scope_locations;

DROP INDEX IF EXISTS idx_role_assignments_scope_type;

ALTER TABLE role_assignments
DROP COLUMN IF EXISTS scope_type;
