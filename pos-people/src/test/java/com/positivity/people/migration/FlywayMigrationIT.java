package com.positivity.people.migration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the full application context against a real Postgres (Testcontainers) so the collapsed
 * Flyway baseline (V1) and the repeatable seeds run, then Hibernate validates the entity mappings
 * (ddl-auto=validate). Context startup succeeding proves the migration, the seed SQL, and the JPA
 * entities all agree.
 *
 * <p>This is the durable guard for the class of breakages that bricked deploys before — untested
 * SQL, because the default tests use H2 with Flyway disabled (e.g. a migration writing to a table
 * in another service's DB, or a seed referencing persons it never inserts). Requires Docker.
 */
@SpringBootTest
@ActiveProfiles("pg")
@Testcontainers
class FlywayMigrationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.positivity.people.internal.client.SecurityServiceClient securityServiceClient;

    @Autowired
    private DataSource dataSource;

    @Test
    void baselineAndSeedsApply_andStructuralChangesTookEffect() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Employee table exists and is populated; person no longer carries employment columns.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee", Integer.class))
                .isGreaterThanOrEqualTo(39);
        assertThat(droppedColumn(jdbc, "person", "employee_number")).isTrue();
        assertThat(droppedColumn(jdbc, "person", "status")).isTrue();
        assertThat(droppedColumn(jdbc, "person", "contact_info_json")).isTrue();

        // Assignments reference employee; user_person_links is keyed by username.
        assertThat(hasColumn(jdbc, "employee_location_assignment", "employee_id"))
                .isTrue();
        assertThat(hasColumn(jdbc, "user_person_links", "username")).isTrue();
        assertThat(droppedColumn(jdbc, "user_person_links", "user_id")).isTrue();

        // Seeds loaded: persons + employees + links.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM person", Integer.class))
                .isGreaterThanOrEqualTo(40);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_person_links", Integer.class))
                .isGreaterThanOrEqualTo(1);
    }

    private boolean hasColumn(JdbcTemplate jdbc, String table, String column) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema='public'"
                        + " AND table_name=? AND column_name=?",
                Integer.class,
                table,
                column);
        return n != null && n > 0;
    }

    private boolean droppedColumn(JdbcTemplate jdbc, String table, String column) {
        return !hasColumn(jdbc, table, column);
    }
}
