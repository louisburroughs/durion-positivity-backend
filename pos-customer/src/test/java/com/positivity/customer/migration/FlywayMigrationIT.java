package com.positivity.customer.migration;

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
 * Boots the full application context against a real Postgres (Testcontainers) so that
 * the entire Flyway migration chain (V1..Vn) and the repeatable seed run, then Hibernate
 * validates the entity mappings (ddl-auto=validate). Context startup succeeding proves the
 * migrations, the seed SQL, and the JPA entities all agree.
 *
 * <p>Added for issue #684: the three rollout breakages (seed comma, two {@code primary}
 * field mismatches) were untested SQL because the default tests use H2 with Flyway
 * disabled. This test exercises the real DDL + seed in CI. Requires Docker.
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

    /**
     * Context loads only if every migration + the repeatable seed applied cleanly and the
     * schema validated against the entities. We additionally assert the thin-link drops
     * from issue #684 actually took effect.
     */
    @Test
    void migrationsAndSeedApply_andThinLinkDropsTookEffect() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 2c.3: contact_point table dropped.
        Integer contactPointTables = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'contact_point'",
                Integer.class);
        assertThat(contactPointTables).isZero();

        // Step 4: person_party name columns dropped.
        Integer nameColumns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'person_party' "
                        + "AND column_name IN ('first_name', 'last_name')",
                Integer.class);
        assertThat(nameColumns).isZero();

        // Seed still loads the person_party rows (FK targets for party_relationship).
        Integer persons = jdbc.queryForObject("SELECT count(*) FROM person_party", Integer.class);
        assertThat(persons).isGreaterThanOrEqualTo(70);
    }
}
