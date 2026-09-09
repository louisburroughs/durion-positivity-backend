package com.positivity.securityservice.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises {@code V40__migrate_user_roles_to_role_assignments.sql} directly against a real
 * Postgres (ADR-0061 amendment, 2026-09-09, #1914 phase 2).
 *
 * <p>Deliberately not a {@code @SpringBootTest}: the application's own Flyway bean
 * ({@code FlywayConfig}) always migrates a fresh datasource straight to the latest version at
 * context startup, which would apply V40 — and drop {@code user_roles} — before any test method
 * ran. This class drives Flyway itself so it can stop the chain at V39 (the schema and repeatable
 * seeds an environment upgrading from phase 1 would actually have), seed {@code user_roles} rows
 * the way phase 1 left them, then advance to V40 and assert the result: every row migrates to an
 * open-ended {@code role_assignments} row, a pair already effectively assigned is skipped rather
 * than duplicated, and {@code user_roles} is gone.
 *
 * <p>Requires Docker.
 */
@Testcontainers
@DisplayName("V40 user_roles -> role_assignments migration (Postgres)")
class V40MigrateUserRolesToRoleAssignmentsIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * A throwaway placeholder value: {@code R__seed_reference_security.sql} interpolates it for a
     * container this test class alone owns and tears down, so nothing ever authenticates as the
     * seeded admin here.
     */
    private static final String SEED_ADMIN_PASSWORD_HASH_PLACEHOLDER = "not-a-real-hash-v40-it";

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    /** A fresh {@link Flyway} instance targeting {@code version}; the schema history table on the
     * shared container is what actually tracks progress, so two instances pointed at different
     * targets can safely be used one after another. */
    private static Flyway flywayTo(String version) {
        return Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .placeholders(Map.of("seed_admin_password_hash", SEED_ADMIN_PASSWORD_HASH_PLACEHOLDER))
                .target(version)
                .load();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    @Test
    @DisplayName("migrates every user_roles row into an open-ended role_assignments row, skips a pair "
            + "already effectively assigned, and drops user_roles")
    void migratesUserRolesIntoRoleAssignments() {
        flywayTo("39").migrate();
        assertThat(tableExists("user_roles"))
                .as("user_roles must still exist pre-V40")
                .isTrue();

        // (1) A fresh user_roles row with no role_assignments counterpart — the common case.
        UUID freshUserId = UUID.randomUUID();
        UUID freshRoleId = UUID.randomUUID();
        OffsetDateTime freshUserCreatedAt = OffsetDateTime.parse("2025-03-01T00:00:00Z");
        jdbc().update(
                        "INSERT INTO roles (id, name, description, created_at, created_by) "
                                + "VALUES (?, 'V40_IT_FRESH_ROLE', 'V40 migration fixture', NOW(), 'v40-it')",
                        freshRoleId);
        jdbc().update(
                        "INSERT INTO users (id, username, password, enabled, created_at, updated_at) "
                                + "VALUES (?, 'v40-it-fresh-user', 'x', true, ?, ?)",
                        freshUserId,
                        freshUserCreatedAt,
                        freshUserCreatedAt);
        jdbc().update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", freshUserId, freshRoleId);

        // (2) A user_roles row duplicating a pair the repeatable seed already granted through
        // role_assignments — proves the skip-on-existing half-open-window check.
        UUID seededUserId = jdbc().queryForObject("SELECT id FROM users WHERE username = 'marcus.webb'", UUID.class);
        UUID seededRoleId =
                jdbc().queryForObject("SELECT id FROM roles WHERE name = 'SYSTEM_ADMINISTRATOR'", UUID.class);
        int assignmentsBeforeForSeededPair = countAssignments(seededUserId, seededRoleId);
        assertThat(assignmentsBeforeForSeededPair)
                .as("the repeatable seed must already have granted this pair through role_assignments")
                .isEqualTo(1);
        jdbc().update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", seededUserId, seededRoleId);

        flywayTo("40").migrate();

        assertThat(tableExists("user_roles")).as("V40 must drop user_roles").isFalse();

        // (1) The fresh pair migrated to an open-ended assignment starting at the user's created_at.
        Map<String, Object> migrated = jdbc().queryForMap(
                        "SELECT effective_start_date, effective_end_date, revoked_at, created_by "
                                + "FROM role_assignments WHERE user_id = ? AND role_id = ?",
                        freshUserId,
                        freshRoleId);
        assertThat(((OffsetDateTime) migrated.get("effective_start_date")).toInstant())
                .isEqualTo(freshUserCreatedAt.toInstant());
        assertThat(migrated.get("effective_end_date")).isNull();
        assertThat(migrated.get("revoked_at")).isNull();
        assertThat(migrated.get("created_by")).isEqualTo("V40__migrate_user_roles_to_role_assignments");

        // (2) The already-effective pair was not duplicated.
        assertThat(countAssignments(seededUserId, seededRoleId))
                .as("a pair already effective now must not gain a second, overlapping assignment")
                .isEqualTo(assignmentsBeforeForSeededPair);
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbc().queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                        Integer.class,
                        tableName);
        return count != null && count > 0;
    }

    private int countAssignments(UUID userId, UUID roleId) {
        Integer count = jdbc().queryForObject(
                        "SELECT count(*) FROM role_assignments WHERE user_id = ? AND role_id = ?",
                        Integer.class,
                        userId,
                        roleId);
        return count == null ? 0 : count;
    }
}
