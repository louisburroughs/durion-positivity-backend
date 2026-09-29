package com.positivity.accounting;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The Postgres every real-database test in this module shares, arranged the way Compose and the alpha
 * host are (ADR-0062 §3, layer 2): the server's superuser owns the schema and runs Flyway, and the
 * application pool connects as a shared non-owner role, created here with the same grants {@code
 * postgres/init-tenancy.sh} makes.
 *
 * <p>Two ways to get that server:
 *
 * <ul>
 *   <li><b>Testcontainers (default).</b> One {@code postgres:16-alpine} container for the whole test
 *       JVM, started lazily from the register methods (so test discovery, which loads every test class,
 *       never needs Docker) and never stopped by a test class: the Spring contexts these tests share are
 *       cached across classes, and they hold this container's port. A per-class Testcontainers-managed
 *       container would be stopped after the first class while the second reused its cached context
 *       against it. Ryuk reaps the container at JVM exit. It replaces the six separate container
 *       bootstraps this module had grown, one per real-Postgres IT.
 *   <li><b>An already-running server</b> when {@value #URL_ENV}, {@value #USER_ENV} and {@value
 *       #PASSWORD_ENV} are set: a developer's local Postgres, or the alpha host's through an SSH
 *       tunnel, for machines and sessions without Docker. The URL names the server and a maintenance
 *       database ({@code jdbc:postgresql://localhost:5433/postgres}); the role must be able to CREATE
 *       DATABASE and CREATE ROLE, which the container superuser alpha runs as can. This mode never
 *       touches an existing database or role: every database the tests use is created under a per-run
 *       prefix ({@code acct_test_<token>_…}) and dropped at JVM exit, and the application role is a
 *       per-run one ({@code pos_app_test_<token>}), so the target's own {@code pos_app} and its password
 *       stay out of it. Set {@value #KEEP_ENV} to keep the databases for a look afterwards; drop them by
 *       hand then ({@code SELECT datname FROM pg_database WHERE datname LIKE 'acct_test_%'}).
 * </ul>
 *
 * <p>The choice and the URL arithmetic are covered by {@code AccountingPostgresContainerTest}; the
 * create-then-drop lifecycle of the external mode by {@code AccountingPostgresContainerExternalModeIT},
 * which points a second {@link Server} at whichever Postgres the run has.
 *
 * <h2>Shared database versus an isolated one</h2>
 *
 * Most tests take {@link #registerDataSourceProperties}, which points them at the shared default
 * database: they roll their work back, so sharing costs nothing. A test that <em>commits</em> — and
 * several of this module's ledger ITs must, because what they prove is what survives a commit — takes
 * {@link #registerIsolatedDatabase} instead and gets a database of its own on the same server. Those
 * tests clear whole tables in their setup, including seeded chart-of-accounts rows another test's
 * report assertions depend on, so a shared database would make them interfere in a way that reads as a
 * flaky report rather than as a fixture collision.
 */
public final class AccountingPostgresContainer {

    /** JDBC URL of an already-running server and its maintenance database; unset means Testcontainers. */
    public static final String URL_ENV = "POS_TEST_PG_URL";

    /** Owner role on that server: superuser, or at least CREATEDB and CREATEROLE. */
    public static final String USER_ENV = "POS_TEST_PG_USER";

    /** Its password. */
    public static final String PASSWORD_ENV = "POS_TEST_PG_PASSWORD";

    /** Any value: leave the per-run databases and role in place at JVM exit instead of dropping them. */
    public static final String KEEP_ENV = "POS_TEST_PG_KEEP";

    private static final Logger log = LoggerFactory.getLogger(AccountingPostgresContainer.class);

    /** The server this JVM's tests share. Package-private so the helper's own tests can point a second one at it. */
    static final Server SERVER = Server.fromEnvironment(System.getenv());

    /**
     * The non-owner role the application pool connects as; it holds no BYPASSRLS. {@code pos_app} in
     * the container, a per-run name on an external server.
     */
    public static final String APP_ROLE = SERVER.appRole();

    private AccountingPostgresContainer() {}

    /**
     * Points a Spring context at the shared default database: the pool at the application role, Flyway
     * at the owner. Starts the container (or checks the external server) and creates the role on first
     * use.
     *
     * @param registry the registry the calling {@code @DynamicPropertySource} was handed
     */
    public static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        SERVER.registerSharedDatabase(registry);
    }

    /**
     * Points a Spring context at a database of its own on the shared server, connected as the owner —
     * the arrangement the module's committing ledger ITs had when each carried its own container, minus
     * the container.
     *
     * @param registry the registry the calling {@code @DynamicPropertySource} was handed
     * @param name the database to use, created on first request; one per test class that commits
     */
    public static void registerIsolatedDatabase(DynamicPropertyRegistry registry, String name) {
        SERVER.registerIsolatedDatabase(registry, name);
    }

    /**
     * The owner on the default database: owns every table, runs Flyway, and bypasses RLS like the alpha
     * owner does. Used by the conformance test to read the catalog the application role cannot.
     *
     * @return a datasource connected as the schema owner
     */
    public static DataSource ownerDataSource() {
        return SERVER.ownerDataSource();
    }

    /**
     * The owner on a database of its own, for a test that drives raw SQL rather than a Spring context.
     *
     * @param name the database to use, created on first request
     * @return a datasource connected as that database's owner
     */
    public static DataSource ownerDataSource(String name) {
        return SERVER.ownerDataSource(name);
    }

    static DataSource dataSource(String url, String user, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    /**
     * Where the tests' Postgres lives — the Testcontainers container, or an already-running server — and
     * what this run has created on it. One instance serves the whole JVM ({@link #SERVER}); the helper's
     * own tests build others.
     */
    static final class Server {

        /** Null on an external server. */
        private final PostgreSQLContainer<?> container;

        private final String maintenanceUrl;
        private final String ownerUser;
        private final String ownerPassword;
        private final String appRole;
        private final String appPassword;
        private final String databasePrefix;
        private final boolean keep;
        private final Set<String> createdDatabases = new LinkedHashSet<>();
        private boolean checked;
        private boolean roleCreated;
        private boolean cleanupRegistered;

        private Server(
                PostgreSQLContainer<?> container,
                String maintenanceUrl,
                String ownerUser,
                String ownerPassword,
                String appRole,
                String appPassword,
                String databasePrefix,
                boolean keep) {
            this.container = container;
            this.maintenanceUrl = maintenanceUrl;
            this.ownerUser = ownerUser;
            this.ownerPassword = ownerPassword;
            this.appRole = appRole;
            this.appPassword = appPassword;
            this.databasePrefix = databasePrefix;
            this.keep = keep;
        }

        /**
         * The container unless {@value #URL_ENV} is set; then the external server, and the other two
         * variables must be set too.
         *
         * @param environment normally {@code System.getenv()}
         * @return the server the variables describe, not yet started or checked
         */
        static Server fromEnvironment(Map<String, String> environment) {
            String url = environment.get(URL_ENV);
            if (url == null || url.isBlank()) {
                return container();
            }
            return external(
                    url.trim(),
                    require(environment, USER_ENV),
                    require(environment, PASSWORD_ENV),
                    environment.get(KEEP_ENV) != null);
        }

        /**
         * An already-running server, addressed with per-run database and role names.
         *
         * @param maintenanceUrl the server and its maintenance database
         * @param ownerUser a role that can CREATE DATABASE and CREATE ROLE
         * @param ownerPassword its password
         * @param keep whether to leave the per-run databases and role in place at JVM exit
         * @return the server, not yet checked
         */
        static Server external(String maintenanceUrl, String ownerUser, String ownerPassword, boolean keep) {
            String token = UUID.randomUUID().toString().substring(0, 8);
            return new Server(
                    null,
                    maintenanceUrl,
                    ownerUser,
                    ownerPassword,
                    "pos_app_test_" + token,
                    UUID.randomUUID().toString(),
                    "acct_test_" + token + "_",
                    keep);
        }

        /**
         * {@code max_connections} is raised from the image default of 100 because one test JVM holds
         * every Spring context it has loaded, each with its own pool: this module has more
         * Postgres-backed context shapes than 100 connections divided by a default pool, and the
         * overflow surfaces as {@code FATAL: remaining connection slots are reserved} while a context
         * that has nothing to do with the failing test is being built.
         */
        static Server container() {
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withCommand("postgres", "-c", "max_connections=300");
            return new Server(postgres, null, null, null, "pos_app", "pos_app-test-only", "", false);
        }

        private static String require(Map<String, String> environment, String variable) {
            String value = environment.get(variable);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(URL_ENV + " is set, so " + variable + " must be set too");
            }
            return value;
        }

        /**
         * {@code url} with its database path swapped for {@code database}. Only the path is touched: a
         * query string stays as it is, slashes inside its values included ({@code sslrootcert=/tmp/root.crt}),
         * and a URL that names no database gets one appended.
         */
        static String withDatabase(String url, String database) {
            int query = url.indexOf('?');
            String base = query < 0 ? url : url.substring(0, query);
            String suffix = query < 0 ? "" : url.substring(query);
            int authority = base.indexOf("//");
            int slash = base.lastIndexOf('/');
            if (slash < 0 || (authority >= 0 && slash <= authority + 1)) {
                return base + "/" + database + suffix;
            }
            return base.substring(0, slash + 1) + database + suffix;
        }

        synchronized void registerSharedDatabase(DynamicPropertyRegistry registry) {
            start();
            register(registry, defaultDatabaseUrl(), appRole, appPassword);
        }

        synchronized void registerIsolatedDatabase(DynamicPropertyRegistry registry, String name) {
            start();
            String database = createDatabase(name);
            register(registry, jdbcUrlFor(database), ownerUser(), ownerPassword());
        }

        synchronized DataSource ownerDataSource() {
            start();
            return dataSource(defaultDatabaseUrl(), ownerUser(), ownerPassword());
        }

        synchronized DataSource ownerDataSource(String name) {
            start();
            String database = createDatabase(name);
            return dataSource(jdbcUrlFor(database), ownerUser(), ownerPassword());
        }

        private void register(DynamicPropertyRegistry registry, String url, String user, String password) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> user);
            registry.add("spring.datasource.password", () -> password);
            registry.add("spring.flyway.url", () -> url);
            registry.add("spring.flyway.user", this::ownerUser);
            registry.add("spring.flyway.password", this::ownerPassword);
        }

        /**
         * Starts the container, or proves the external server answers before any context is built; then
         * makes sure the shared database and the application role exist. Synchronized (as are the callers)
         * because two test classes building their contexts at once must not both do any of that.
         */
        synchronized void start() {
            if (container != null) {
                if (!container.isRunning()) {
                    container.start();
                }
            } else if (!checked) {
                try (Connection connection =
                        dataSource(maintenanceUrl, ownerUser, ownerPassword).getConnection()) {
                    log.info(
                            "Postgres for tests: {} as {} (external server; databases {}*, role {}, {})",
                            maintenanceUrl,
                            ownerUser,
                            databasePrefix,
                            appRole,
                            keep ? "kept at JVM exit" : "dropped at JVM exit");
                    checked = true;
                } catch (SQLException e) {
                    throw new IllegalStateException(
                            URL_ENV + "=" + maintenanceUrl + " does not answer as " + ownerUser
                                    + ": " + e.getMessage() + ". Fix the tunnel or the credentials, or unset " + URL_ENV
                                    + " to use Testcontainers.",
                            e);
                }
            }
            if (external()) {
                createDatabaseNamed(defaultDatabase());
                registerCleanup();
            }
            ensureApplicationRole();
        }

        /** Creates the database for a logical test name on first request and returns its real name. */
        private String createDatabase(String name) {
            String database = databaseName(name);
            createDatabaseNamed(database);
            return database;
        }

        private void createDatabaseNamed(String database) {
            if (!createdDatabases.add(database)) {
                return;
            }
            try (Connection connection = ownerConnection(maintenanceUrl());
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE \"" + database + "\"");
            } catch (SQLException e) {
                createdDatabases.remove(database);
                throw new IllegalStateException("Unable to create the test database " + database, e);
            }
        }

        /** Mirrors postgres/init-tenancy.sh: LOGIN, no superuser, no BYPASSRLS, DML through default privileges. */
        private void ensureApplicationRole() {
            if (roleCreated) {
                return;
            }
            try (Connection connection = ownerConnection(defaultDatabaseUrl());
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE ROLE " + appRole + " LOGIN PASSWORD '" + appPassword
                        + "' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT");
                statement.execute("GRANT CONNECT ON DATABASE \"" + defaultDatabase() + "\" TO " + appRole);
                statement.execute("GRANT USAGE ON SCHEMA public TO " + appRole);
                statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE \"" + ownerUser()
                        + "\" IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + appRole);
                statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE \"" + ownerUser()
                        + "\" IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO " + appRole);
                roleCreated = true;
            } catch (SQLException e) {
                throw new IllegalStateException("Unable to create the " + appRole + " role on the test Postgres", e);
            }
        }

        /**
         * External mode only: drop what this run created once the JVM exits, unless asked to keep it. Ryuk
         * does the same for the container.
         */
        private void registerCleanup() {
            if (cleanupRegistered) {
                return;
            }
            cleanupRegistered = true;
            if (keep) {
                log.info(
                        "Keeping the per-run test databases {}* and role {} on {} ({} is set)",
                        databasePrefix,
                        appRole,
                        maintenanceUrl,
                        KEEP_ENV);
                return;
            }
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(this::dropRunArtifacts, "accounting-test-postgres-cleanup"));
        }

        /**
         * Drops every database this run created and then its application role. {@code WITH (FORCE)} closes
         * the pools the cached Spring contexts still hold; the role goes last because the databases carry
         * its grants. Idempotent: a database or role already gone is skipped.
         */
        synchronized void dropRunArtifacts() {
            List<String> databases = new ArrayList<>(createdDatabases);
            try (Connection connection = ownerConnection(maintenanceUrl());
                    Statement statement = connection.createStatement()) {
                for (String database : databases) {
                    statement.execute("DROP DATABASE IF EXISTS \"" + database + "\" WITH (FORCE)");
                    createdDatabases.remove(database);
                }
                if (roleCreated) {
                    statement.execute("DROP ROLE IF EXISTS " + appRole);
                    roleCreated = false;
                }
            } catch (SQLException e) {
                log.warn(
                        "Could not drop the per-run test databases {} and role {} on {}: {}. Drop them by hand.",
                        databases,
                        appRole,
                        maintenanceUrl(),
                        e.getMessage());
            }
        }

        private Connection ownerConnection(String url) throws SQLException {
            return dataSource(url, ownerUser(), ownerPassword()).getConnection();
        }

        boolean external() {
            return container == null;
        }

        boolean keep() {
            return keep;
        }

        String appRole() {
            return appRole;
        }

        String appPassword() {
            return appPassword;
        }

        String ownerUser() {
            return container != null ? container.getUsername() : ownerUser;
        }

        String ownerPassword() {
            return container != null ? container.getPassword() : ownerPassword;
        }

        /** The server's own maintenance database: the container's default one, or the one the URL names. */
        String maintenanceUrl() {
            return container != null ? container.getJdbcUrl() : maintenanceUrl;
        }

        /** The database the rollback-only tests share: the container's default one, or a per-run one. */
        String defaultDatabase() {
            return container != null ? container.getDatabaseName() : databasePrefix + "main";
        }

        String defaultDatabaseUrl() {
            return container != null ? container.getJdbcUrl() : jdbcUrlFor(defaultDatabase());
        }

        /** A logical test database name made unique to this run on a shared server. */
        String databaseName(String name) {
            return databasePrefix + name;
        }

        /** The maintenance URL with its database swapped for {@code database}. */
        String jdbcUrlFor(String database) {
            return withDatabase(maintenanceUrl(), database);
        }

        /** The databases this run has created and not yet dropped, in creation order. */
        Set<String> createdDatabases() {
            return Collections.unmodifiableSet(createdDatabases);
        }
    }
}
