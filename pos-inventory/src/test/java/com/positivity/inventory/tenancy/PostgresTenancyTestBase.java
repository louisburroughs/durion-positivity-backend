package com.positivity.inventory.tenancy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A Postgres 16 container laid out the way Compose and the alpha host are (ADR-0062 §3, layer 2):
 * the container's superuser owns the schema and runs Flyway; the application pool connects as the
 * shared non-owner {@code pos_app} role, created here with the same grants {@code
 * postgres/init-tenancy.sh} makes. The runtime is strict ({@code pg} profile): nothing binds a
 * tenant unless the test does.
 *
 * <p>Requires Docker.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pg")
public abstract class PostgresTenancyTestBase {

    static final String APP_ROLE = "pos_app";
    static final String APP_PASSWORD = "pos_app-test-only";

    /**
     * Started by the Testcontainers extension, not a static initializer: test discovery (the ArchUnit
     * engine scans every test class) must not need Docker.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static volatile boolean roleCreated;

    /** Mirrors postgres/init-tenancy.sh: LOGIN, no superuser, no BYPASSRLS, DML through default privileges. */
    private static synchronized void ensureApplicationRole() {
        if (roleCreated) {
            return;
        }
        try (Connection owner = ownerDataSource().getConnection();
                Statement statement = owner.createStatement()) {
            statement.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD
                    + "' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT");
            statement.execute("GRANT CONNECT ON DATABASE " + POSTGRES.getDatabaseName() + " TO " + APP_ROLE);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + POSTGRES.getUsername()
                    + " IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + APP_ROLE);
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + POSTGRES.getUsername()
                    + " IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO " + APP_ROLE);
            roleCreated = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to create the pos_app role in the test container", e);
        }
    }

    /** The container superuser: owns every table, runs Flyway, and bypasses RLS like the alpha owner does. */
    static DataSource ownerDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    /** Runs once the container is up and before the Spring context starts, so the role exists for Flyway and the pool. */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        ensureApplicationRole();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }
}
