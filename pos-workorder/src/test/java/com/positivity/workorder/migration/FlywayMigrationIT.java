package com.positivity.workorder.migration;

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
 * Boots the full application context against a real Postgres (Testcontainers) so the entire Flyway
 * migration chain (V1 baseline .. Vn) runs and Hibernate then validates the entity mappings
 * (ddl-auto=validate). Context startup succeeding proves the migrations and the JPA entities agree.
 *
 * <p>Added for issue #711: the on-behalf audit columns ({@code on_behalf_reason},
 * {@code acted_by_user_id}) added in {@code V2} were otherwise untested SQL, because the default
 * contract suite uses H2 with Flyway disabled. This test exercises the real Postgres DDL in CI and
 * fails on any entity/migration drift. Requires Docker.
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
     * Context loads only if every migration applied cleanly and the schema validated against the
     * entities. Additionally assert the issue #711 on-behalf audit columns took effect with the
     * expected types.
     */
    @Test
    void migrationsApply_andOnBehalfAuditColumnsExist() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer reasonColumn = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'workorder_labor_entry' "
                        + "AND column_name = 'on_behalf_reason' AND data_type = 'text'",
                Integer.class);
        assertThat(reasonColumn).isEqualTo(1);

        Integer actedByColumn = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'workorder_labor_entry' "
                        + "AND column_name = 'acted_by_user_id' AND data_type = 'uuid'",
                Integer.class);
        assertThat(actedByColumn).isEqualTo(1);
    }
}
