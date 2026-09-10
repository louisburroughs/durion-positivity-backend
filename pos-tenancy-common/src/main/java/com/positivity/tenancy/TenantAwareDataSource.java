package com.positivity.tenancy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Binds {@code app.current_tenant} on every connection handed to the application, from
 * {@link TenantContext} (ADR-0062 §2).
 *
 * <p>Postgres row-level security is the authoritative layer, and every {@code tenant_isolation}
 * policy reads this one session setting. The binding therefore has to happen per checkout rather
 * than per connection: a pooled connection outlives the request that borrowed it, so a value set
 * once at connect time would leak one tenant's scope into the next borrower.
 *
 * <p>Both halves matter. On checkout the setting is written; on {@code close()}, before the
 * connection returns to the pool, it is reset. A connection that came back still carrying a tenant
 * would hand that tenant's visibility to whoever borrows it next.
 *
 * <p>With nothing bound the setting is reset rather than written, so scoped tables read as empty
 * and refuse inserts. Failing closed is deliberate: the alternative is a query that silently
 * crosses tenants.
 *
 * <p>The setting is session-level ({@code set_config(..., false)}) because services connect to
 * Postgres directly. Adopting PgBouncer transaction pooling later requires moving to
 * {@code SET LOCAL} inside mandatory transactions; ADR-0062 §2 records that constraint.
 *
 * <p>On anything but Postgres this is a no-op. The tenancy schema is Postgres-only — H2 has neither
 * row-level security nor {@code set_config} — so the H2 {@code dev} profile and the H2 test slices
 * would otherwise fail on the first checkout with "Function SET_CONFIG not found". The product is
 * read once from the first connection's metadata and remembered.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String POSTGRES = "PostgreSQL";

    private final @Nullable UUID fallbackTenantId;

    /** Null until the first connection tells us what this datasource actually talks to. */
    private volatile @Nullable Boolean postgres;

    /**
     * @param delegate the module's real pooled datasource
     * @param fallbackTenantId bound when the thread carries no tenant, or {@code null} to fail
     *     closed. Transitional only: it exists so services keep serving while the access token
     *     still carries no {@code tid} claim (plan WS2b), and is removed with that claim.
     */
    public TenantAwareDataSource(@NonNull DataSource delegate, @Nullable UUID fallbackTenantId) {
        super(delegate);
        this.fallbackTenantId = fallbackTenantId;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection connection) throws SQLException {
        try {
            if (!isPostgres(connection)) {
                return connection;
            }
            applyTenant(connection, resolveTenant());
        } catch (SQLException | RuntimeException e) {
            closeQuietly(connection);
            throw e;
        }
        return proxy(connection);
    }

    private boolean isPostgres(Connection connection) throws SQLException {
        Boolean known = postgres;
        if (known == null) {
            known = POSTGRES.equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
            postgres = known;
        }
        return known;
    }

    private @Nullable UUID resolveTenant() {
        return TenantContext.current().orElse(fallbackTenantId);
    }

    /**
     * Writes the setting, or resets it when no tenant is bound. Reset rather than "leave alone":
     * the connection may carry a previous borrower's value.
     */
    private static void applyTenant(Connection connection, @Nullable UUID tenantId) throws SQLException {
        if (tenantId == null) {
            resetTenant(connection);
            return;
        }
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT set_config('" + TenancyConstants.TENANT_SETTING + "', ?, false)")) {
            statement.setString(1, tenantId.toString());
            statement.execute();
        }
    }

    private static void resetTenant(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('" + TenancyConstants.TENANT_SETTING + "', '', false)")) {
            statement.execute();
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // The caller is already failing; the original exception is the useful one.
        }
    }

    private static Connection proxy(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                TenantAwareDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new ResetOnClose(connection));
    }

    /** Resets the setting as the connection goes back to the pool. */
    private record ResetOnClose(Connection target) implements InvocationHandler {

        @Override
        public @Nullable Object invoke(Object proxy, Method method, Object @Nullable [] args) throws Throwable {
            if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
                try {
                    if (!target.isClosed()) {
                        resetTenant(target);
                    }
                } catch (SQLException ignored) {
                    // A connection that cannot be reset is being discarded anyway; closing it is
                    // what matters, and swallowing here keeps close() from throwing on cleanup.
                }
                target.close();
                return null;
            }
            if ("unwrap".equals(method.getName()) && args != null && args.length == 1) {
                Class<?> type = (Class<?>) args[0];
                if (type.isInstance(proxy)) {
                    return proxy;
                }
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }
    }
}
