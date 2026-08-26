-- H2-only test schema patch.
--
-- The default test profile builds its schema from the JPA entities
-- (ddl-auto: create-drop) with Flyway disabled, so any column that exists only
-- in a migration is invisible here. role_permissions is mapped as a plain
-- @ManyToMany join table on Role.permissions, which means Hibernate generates
-- exactly (role_id, permission_id) and nothing else.
--
-- V30 adds granted_at/granted_by and RoleRepository.recordGrantProvenance
-- writes them natively (#1512). Without this patch every grant through the
-- admin API fails on H2 with "Column GRANTED_AT not found" — a schema gap in
-- the test profile, not a defect in the query.
--
-- Runs after Hibernate's DDL because application-test.yml sets
-- defer-datasource-initialization: true. It does not run under the `pg`
-- profile: spring.sql.init.mode defaults to `embedded`, and the Testcontainers
-- Postgres there gets these columns from V30 itself.
--
-- Keep in sync with V30__add_role_permission_audit_columns.sql.

ALTER TABLE role_permissions ADD COLUMN IF NOT EXISTS granted_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE role_permissions ADD COLUMN IF NOT EXISTS granted_by VARCHAR(255);
