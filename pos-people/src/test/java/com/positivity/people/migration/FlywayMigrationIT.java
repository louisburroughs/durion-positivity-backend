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

    @Autowired
    private DataSource dataSource;

    @Test
    void baselineAndSeedsApply_andStructuralChangesTookEffect() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // HR-only schema (ADR-0044 Phase 3.2, #875): identity tables dropped, replicas + outbox
        // created; employees survive the split.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee", Integer.class))
                .isGreaterThanOrEqualTo(39);
        assertThat(droppedTable(jdbc, "person")).isTrue();
        assertThat(droppedTable(jdbc, "person_contact_point")).isTrue();
        assertThat(droppedTable(jdbc, "user_person_links")).isTrue();

        assertThat(hasColumn(jdbc, "ext_people_contact_person", "primary_email"))
                .isTrue();
        assertThat(hasColumn(jdbc, "ext_people_contact_user_link", "username")).isTrue();
        assertThat(hasColumn(jdbc, "event_outbox", "record_key")).isTrue();
        assertThat(hasColumn(jdbc, "processed_events", "owner")).isTrue();

        // Dev-bootstrap replica seeds loaded (names + usernames for HR views).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ext_people_contact_person", Integer.class))
                .isGreaterThanOrEqualTo(39);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ext_people_contact_user_link", Integer.class))
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

    private boolean droppedTable(JdbcTemplate jdbc, String table) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name=?",
                Integer.class,
                table);
        return n != null && n == 0;
    }
}
