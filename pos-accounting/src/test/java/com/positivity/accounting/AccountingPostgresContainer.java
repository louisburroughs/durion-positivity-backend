package com.positivity.accounting;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
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
 * <p>One container for the whole test JVM, started lazily from the register methods (so test
 * discovery, which loads every test class, never needs Docker) and never stopped by a test class:
 * the Spring contexts these tests share are cached across classes, and they hold this container's
 * port. A per-class Testcontainers-managed container would be stopped after the first class while
 * the second reused its cached context against it. Ryuk reaps the container at JVM exit.
 *
 * <p>It replaces the six separate container bootstraps this module had grown — one per real-Postgres
 * IT — which meant six Postgres servers running at once for tests that could have shared one.
 *
 * <h2>Shared database versus an isolated one</h2>
 *
 * Most tests take {@link #registerDataSourceProperties}, which points them at the container's
 * default database: they roll their work back, so sharing costs nothing. A test that <em>commits</em>
 * — and several of this module's ledger ITs must, because what they prove is what survives a commit
 * — takes {@link #registerIsolatedDatabase} instead and gets a database of its own inside the same
 * container. Those tests clear whole tables in their setup, including seeded chart-of-accounts rows
 * another test's report assertions depend on, so a shared database would make them interfere in a
 * way that reads as a flaky report rather than as a fixture collision.
 *
 * <p>Requires Docker.
 */
public final class AccountingPostgresContainer {

    /** The non-owner role the application pool connects as; it holds no BYPASSRLS. */
    public static final String APP_ROLE = "pos_app";

    private static final String APP_PASSWORD = "pos_app-test-only";

    /**
     * {@code max_connections} is raised from the image default of 100 because one test JVM holds
     * every Spring context it has loaded, each with its own pool: this module has more
     * Postgres-backed context shapes than 100 connections divided by a default pool, and the
     * overflow surfaces as {@code FATAL: remaining connection slots are reserved} while a context
     * that has nothing to do with the failing test is being built.
     */
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withCommand("postgres", "-c", "max_connections=300");

    private static boolean roleCreated;

    private static final Set<String> CREATED_DATABASES = new HashSet<>();

    private AccountingPostgresContainer() {}

    /**
     * Points a Spring context at the container's default database: the pool at {@code pos_app},
     * Flyway at the owner. Starts the container and creates the role on first use.
     *
     * @param registry the registry the calling {@code @DynamicPropertySource} was handed
     */
    public static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        start();
        register(registry, POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    /**
     * Points a Spring context at a database of its own inside the shared container, connected as the
     * owner — the arrangement the module's committing ledger ITs had when each carried its own
     * container, minus the container.
     *
     * @param registry the registry the calling {@code @DynamicPropertySource} was handed
     * @param name the database to use, created on first request; one per test class that commits
     */
    public static void registerIsolatedDatabase(DynamicPropertyRegistry registry, String name) {
        start();
        createDatabase(name);
        register(registry, jdbcUrlFor(name), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * The container superuser on the default database: owns every table, runs Flyway, and bypasses
     * RLS like the alpha owner does. Used by the conformance test to read the catalog the
     * application role cannot.
     *
     * @return a datasource connected as the schema owner
     */
    public static DataSource ownerDataSource() {
        start();
        return dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * The container superuser on a database of its own, for a test that drives raw SQL rather than a
     * Spring context.
     *
     * @param name the database to use, created on first request
     * @return a datasource connected as that database's owner
     */
    public static DataSource ownerDataSource(String name) {
        start();
        createDatabase(name);
        return dataSource(jdbcUrlFor(name), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void register(DynamicPropertyRegistry registry, String url, String user, String password) {
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    private static DataSource dataSource(String url, String user, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    /** The container's JDBC URL with its database name swapped for {@code name}. */
    private static String jdbcUrlFor(String name) {
        String url = POSTGRES.getJdbcUrl();
        int database = url.lastIndexOf('/');
        int query = url.indexOf('?', database);
        return url.substring(0, database + 1) + name + (query < 0 ? "" : url.substring(query));
    }

    /**
     * Synchronized because two test classes building their contexts at once must not both start the
     * container or both create the role.
     */
    private static synchronized void start() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        ensureApplicationRole();
    }

    private static synchronized void createDatabase(String name) {
        if (!CREATED_DATABASES.add(name)) {
            return;
        }
        try (Connection connection = ownerConnection(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + name + "\"");
            statement.execute("GRANT CONNECT ON DATABASE \"" + name + "\" TO " + APP_ROLE);
        } catch (SQLException e) {
            CREATED_DATABASES.remove(name);
            throw new IllegalStateException("Unable to create the test database " + name, e);
        }
    }

    /** Mirrors postgres/init-tenancy.sh: LOGIN, no superuser, no BYPASSRLS, DML through default privileges. */
    private static void ensureApplicationRole() {
        if (roleCreated) {
            return;
        }
        try (Connection connection = ownerConnection(POSTGRES.getJdbcUrl());
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

    private static Connection ownerConnection(String url) throws SQLException {
        return dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).getConnection();
    }
}
