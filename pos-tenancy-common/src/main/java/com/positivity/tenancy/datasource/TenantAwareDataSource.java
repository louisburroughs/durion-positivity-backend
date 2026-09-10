package com.positivity.tenancy.datasource;

import com.positivity.tenancy.TenantHeaders;
import com.positivity.tenancy.TenantResolver;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Binds the resolved tenant to every connection the pool hands out (ADR-0062 §3, layer 3).
 *
 * <p>On checkout: {@code SELECT set_config('app.current_tenant', ?, false)} with the tenant from
 * {@link TenantResolver}, or {@code RESET app.current_tenant} when none resolves, so a pooled
 * connection never carries a previous checkout's tenant. On {@link Connection#close()}: {@code
 * RESET app.current_tenant} again, then the pool takes the connection back.
 *
 * <p>Session-level rather than {@code SET LOCAL} because services connect to Postgres directly; the
 * plan records that PgBouncer transaction pooling would require {@code SET LOCAL} inside mandatory
 * transactions. {@code RESET} restores the role's default: for the transitional owner role that is
 * the alpha default tenant (ADR-0062 §9), for {@code pos_app} it is unset, and RLS fails closed.
 *
 * <p>Only PostgreSQL connections are bound; on any other product (the H2 dev and test profiles)
 * the wrapper is a pass-through, decided once from the first connection's metadata.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSource.class);

    static final String BIND_SQL = "SELECT set_config('" + TenantHeaders.PG_SETTING + "', ?, false)";
    static final String RESET_SQL = "RESET " + TenantHeaders.PG_SETTING;

    private final TenantResolver tenantResolver;

    /** {@code null} until the first connection tells us whether the product is PostgreSQL. */
    private volatile @Nullable Boolean postgres;

    public TenantAwareDataSource(DataSource target, TenantResolver tenantResolver) {
        super(target);
        this.tenantResolver = tenantResolver;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bind(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bind(obtainTargetDataSource().getConnection(username, password));
    }

    private Connection bind(Connection connection) throws SQLException {
        if (!isPostgres(connection)) {
            return connection;
        }
        try {
            Optional<UUID> tenant = tenantResolver.resolve();
            if (tenant.isPresent()) {
                try (PreparedStatement statement = connection.prepareStatement(BIND_SQL)) {
                    statement.setString(1, tenant.get().toString());
                    statement.execute();
                }
            } else {
                reset(connection);
            }
        } catch (SQLException | RuntimeException e) {
            // Never hand out a connection whose tenant binding is unknown.
            connection.close();
            throw e;
        }
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, new ResettingHandler(connection));
    }

    private static void reset(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(RESET_SQL);
        }
    }

    private boolean isPostgres(Connection connection) throws SQLException {
        Boolean known = postgres;
        if (known == null) {
            String product = connection.getMetaData().getDatabaseProductName();
            known = product != null
                    && product.toLowerCase(java.util.Locale.ROOT).contains("postgres");
            postgres = known;
            if (!known) {
                log.info("TenantAwareDataSource is a pass-through on {} (tenant binding is PostgreSQL-only)", product);
            }
        }
        return known;
    }

    /** Resets the session setting before the pool reclaims the connection. */
    private static final class ResettingHandler implements InvocationHandler {

        private final Connection target;

        ResettingHandler(Connection target) {
            this.target = target;
        }

        @Override
        public @Nullable Object invoke(Object proxy, Method method, Object @Nullable [] args) throws Throwable {
            switch (method.getName()) {
                case "close" -> {
                    try {
                        if (!target.isClosed()) {
                            reset(target);
                        }
                    } catch (SQLException e) {
                        log.warn(
                                "RESET {} failed before returning a connection to the pool",
                                TenantHeaders.PG_SETTING,
                                e);
                    } finally {
                        target.close();
                    }
                    return null;
                }
                case "equals" -> {
                    return args != null && proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "unwrap" -> {
                    Class<?> type = (Class<?>) (args == null ? Connection.class : args[0]);
                    return type.isInstance(proxy) ? proxy : target.unwrap(type);
                }
                case "isWrapperFor" -> {
                    Class<?> type = (Class<?>) (args == null ? Connection.class : args[0]);
                    return type.isInstance(proxy) || target.isWrapperFor(type);
                }
                default -> {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                }
            }
        }
    }
}
