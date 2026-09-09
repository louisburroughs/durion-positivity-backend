package com.positivity.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The binding has to be proven against real row-level security: H2 has neither RLS nor
 * {@code current_setting}, so a test on H2 would pass while the isolation it claims does not exist.
 *
 * <p>The table here mirrors what the flattened baselines produce for a tenant-scoped table: a
 * {@code tenant_id} defaulted from {@code app_current_tenant()}, RLS enabled and forced, and one
 * {@code tenant_isolation} policy.
 *
 * <p>The application pool deliberately connects as a non-superuser that owns nothing. A superuser
 * bypasses row-level security unconditionally — FORCE does not change that — so a test run as the
 * container's default user would pass while proving no isolation at all. This mirrors the
 * {@code pos_app} role of ADR-0062 §2.
 */
@Testcontainers
@DisplayName("TenantAwareDataSource binds app.current_tenant per checkout (Postgres)")
class TenantAwareDataSourceIT {

    private static final UUID TENANT_A = UUID.fromString("01900000-0000-7000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("01900000-0000-7000-8000-00000000000b");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Owner connection: schema setup only, and a superuser, so RLS never applies to it. */
    private static HikariDataSource ownerPool;

    /** What the application uses: non-superuser, owns nothing, subject to every policy. */
    private static HikariDataSource appPool;

    @BeforeAll
    static void schema() throws SQLException {
        ownerPool = pool(POSTGRES.getUsername(), POSTGRES.getPassword(), 2);

        try (Connection c = ownerPool.getConnection();
                Statement s = c.createStatement()) {
            s.execute("""
                    CREATE OR REPLACE FUNCTION public.app_current_tenant() RETURNS uuid
                        LANGUAGE sql STABLE PARALLEL SAFE
                        AS $$ SELECT NULLIF(current_setting('app.current_tenant', true), '')::uuid $$;
                    """);
            s.execute("""
                    CREATE TABLE widget (
                        tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
                        id uuid NOT NULL PRIMARY KEY,
                        label text NOT NULL
                    );
                    """);
            s.execute("ALTER TABLE widget ENABLE ROW LEVEL SECURITY");
            s.execute("ALTER TABLE widget FORCE ROW LEVEL SECURITY");
            s.execute("""
                    CREATE POLICY tenant_isolation ON widget
                        USING (tenant_id = public.app_current_tenant())
                        WITH CHECK (tenant_id = public.app_current_tenant());
                    """);
            s.execute("CREATE ROLE app_role LOGIN PASSWORD 'app_pw' NOSUPERUSER NOBYPASSRLS");
            s.execute("GRANT USAGE ON SCHEMA public TO app_role");
            s.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON widget TO app_role");
            s.execute("GRANT EXECUTE ON FUNCTION public.app_current_tenant() TO app_role");
        }

        // One connection makes the reset-on-close contract observable: the next checkout is
        // necessarily the same physical connection, so a leaked setting would be visible.
        appPool = pool("app_role", "app_pw", 1);
    }

    private static HikariDataSource pool(String username, String password, int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(size);
        return new HikariDataSource(config);
    }

    @AfterAll
    static void close() {
        if (appPool != null) {
            appPool.close();
        }
        if (ownerPool != null) {
            ownerPool.close();
        }
    }

    @BeforeEach
    void emptyTable() throws SQLException {
        // As the owner, which bypasses the policy: each test needs the table in a known state,
        // and no bound tenant could delete another tenant's rows.
        try (Connection c = ownerPool.getConnection();
                Statement s = c.createStatement()) {
            s.execute("TRUNCATE widget");
        }
    }

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    private static DataSource failClosed() {
        return new TenantAwareDataSource(appPool, null);
    }

    private static void insert(DataSource dataSource, String label) throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement()) {
            s.execute("INSERT INTO widget (id, label) VALUES (gen_random_uuid(), '" + label + "')");
        }
    }

    private static int count(DataSource dataSource) throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM widget")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("a row written under one tenant is invisible to another")
    void isolatesTenants() throws SQLException {
        DataSource dataSource = failClosed();

        TenantContext.runAs(TENANT_A, () -> {
            try {
                insert(dataSource, "a-only");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(TenantContext.runAs(TENANT_A, () -> countUnchecked(dataSource)))
                .isEqualTo(1);
        assertThat(TenantContext.runAs(TENANT_B, () -> countUnchecked(dataSource)))
                .isZero();
    }

    @Test
    @DisplayName("with no tenant bound the table reads as empty and refuses inserts")
    void failsClosedWithoutABinding() throws SQLException {
        DataSource dataSource = failClosed();
        TenantContext.runAs(TENANT_A, () -> {
            try {
                insert(dataSource, "a-row");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(count(dataSource)).isZero();
        assertThatThrownBy(() -> insert(dataSource, "unbound")).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("the setting does not survive the connection returning to the pool")
    void resetsOnClose() throws SQLException {
        DataSource dataSource = failClosed();
        TenantContext.runAs(TENANT_A, () -> countUnchecked(dataSource));

        // Same physical connection (pool of one), now borrowed with nothing bound.
        try (Connection c = appPool.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT current_setting('app.current_tenant', true)")) {
            rs.next();
            assertThat(rs.getString(1)).isIn(null, "");
        }
    }

    @Test
    @DisplayName("the fallback tenant applies only when the thread carries none")
    void fallbackAppliesOnlyWhenUnbound() throws SQLException {
        DataSource dataSource = new TenantAwareDataSource(appPool, TENANT_B);

        TenantContext.runAs(TENANT_A, () -> {
            try {
                insert(dataSource, "belongs-to-a");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        // Unbound thread falls back to B, which cannot see A's row.
        assertThat(count(dataSource)).isZero();
        assertThat(TenantContext.runAs(TENANT_A, () -> countUnchecked(dataSource)))
                .isEqualTo(1);
    }

    private static int countUnchecked(DataSource dataSource) {
        try {
            return count(dataSource);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
