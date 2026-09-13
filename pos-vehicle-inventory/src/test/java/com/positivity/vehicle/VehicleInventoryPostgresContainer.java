package com.positivity.vehicle;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The single Postgres 16 container every Postgres-backed test in this module shares, laid out the
 * way Compose and the alpha host are (ADR-0062 §3, layer 2): the container's superuser owns the
 * schema and runs Flyway, and the application pool connects as the shared non-owner {@code pos_app}
 * role, created here with the same grants {@code postgres/init-tenancy.sh} makes.
 *
 * <p>One container for the whole test JVM, started lazily from {@link #registerDataSourceProperties}
 * (so test discovery, which loads every test class, never needs Docker) and never stopped by a test
 * class: the Spring contexts these tests share are cached across classes, and they hold this
 * container's port. A per-class Testcontainers-managed container would be stopped after the first
 * class while the second reused its cached context against it. Ryuk reaps the container at JVM exit.
 *
 * <p>It exists as its own type rather than as fields on a base class because two different base
 * classes need it — the {@code @SpringBootTest}
 * {@link com.positivity.vehicle.tenancy.PostgresTenancyTestBase} and the {@code @DataJpaTest}
 * {@link PostgresSliceTestBase}.
 *
 * <p>Requires Docker.
 */
public final class VehicleInventoryPostgresContainer {

    /** The non-owner role the application pool connects as; it holds no BYPASSRLS. */
    public static final String APP_ROLE = "pos_app";

    private static final String APP_PASSWORD = "pos_app-test-only";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static boolean roleCreated;

    private VehicleInventoryPostgresContainer() {}

    /**
     * Points a Spring context at the container: the pool at {@code pos_app}, Flyway at the owner.
     * Starts the container and creates the role on first use, before the context starts, so the
     * role exists for both.
     */
    public static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * The container superuser: owns every table, runs Flyway, and bypasses RLS like the alpha owner
     * does. Used by the conformance test to read the catalog the application role cannot.
     */
    public static DataSource ownerDataSource() {
        start();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    /** The database the container serves; the conformance test names it in its catalog queries. */
    public static String databaseName() {
        start();
        return POSTGRES.getDatabaseName();
    }

    private static synchronized void start() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        ensureApplicationRole();
    }

    /** Mirrors postgres/init-tenancy.sh: LOGIN, no superuser, no BYPASSRLS, DML through default privileges. */
    private static void ensureApplicationRole() {
        if (roleCreated) {
            return;
        }
        PGSimpleDataSource owner = new PGSimpleDataSource();
        owner.setUrl(POSTGRES.getJdbcUrl());
        owner.setUser(POSTGRES.getUsername());
        owner.setPassword(POSTGRES.getPassword());
        try (Connection connection = owner.getConnection();
                Statement statement = connection.createStatement()) {
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
}
