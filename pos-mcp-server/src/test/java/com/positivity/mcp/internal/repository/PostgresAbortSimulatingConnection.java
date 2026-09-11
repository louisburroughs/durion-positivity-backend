package com.positivity.mcp.internal.repository;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test double that wraps a real JDBC {@link Connection} and adds the one behavior H2 does not
 * reproduce: on PostgreSQL, once a statement on a transaction fails, every later statement on that
 * same transaction is refused ("current transaction is aborted, commands ignored until end of
 * transaction block") until a rollback — to a savepoint, or the whole transaction — clears it.
 *
 * <p>Verified separately against a real H2 connection in {@code MODE=PostgreSQL}: catching a unique-
 * constraint {@link SQLException} there and then running a further statement on the same connection
 * without any rollback succeeds, it does not raise anything resembling Postgres's abort. That means
 * a plain H2 integration test cannot exercise the failure mode {@code
 * ToolPriorityRepositoryImplUpsertAbortRecoveryTest} defends against — a regression there would pass
 * unnoticed against H2 alone. This decorator makes a real H2 connection behave like Postgres for
 * that one property, while the savepoint mechanics under test — {@link
 * java.sql.Connection#setSavepoint(String)}, {@link java.sql.Connection#rollback(java.sql.Savepoint)}
 * — and every other statement run for real against H2 underneath it.
 */
final class PostgresAbortSimulatingConnection implements InvocationHandler {

    /** PostgreSQL's SQLSTATE for "in_failed_sql_transaction". */
    private static final String IN_FAILED_TRANSACTION_SQLSTATE = "25P02";

    private final Connection delegate;
    private final String triggerSqlPrefix;
    private final Runnable afterTriggerStatementExecutes;
    private final AtomicBoolean poisoned = new AtomicBoolean(false);
    private final AtomicBoolean triggerFired = new AtomicBoolean(false);

    private PostgresAbortSimulatingConnection(
            Connection delegate, String triggerSqlPrefix, Runnable afterTriggerStatementExecutes) {
        this.delegate = delegate;
        this.triggerSqlPrefix = triggerSqlPrefix;
        this.afterTriggerStatementExecutes = afterTriggerStatementExecutes;
    }

    /**
     * @param delegate the real connection to wrap
     * @param triggerSqlPrefix the SQL prefix of the one statement whose first successful execution
     *     fires {@code afterTriggerStatementExecutes}
     * @param afterTriggerStatementExecutes runs once, synchronously, immediately after the first
     *     statement whose SQL starts with {@code triggerSqlPrefix} executes successfully — the hook
     *     a test uses to land a competing row on the underlying connection at a point common to
     *     both the old and the new {@code upsertOverlay} implementation, before either has taken a
     *     savepoint (if it takes one at all), so a later rollback to that savepoint never undoes it
     */
    static Connection wrap(Connection delegate, String triggerSqlPrefix, Runnable afterTriggerStatementExecutes) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new PostgresAbortSimulatingConnection(delegate, triggerSqlPrefix, afterTriggerStatementExecutes));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            switch (method.getName()) {
                case "prepareStatement":
                    String sql = (String) args[0];
                    PreparedStatement real = (PreparedStatement) method.invoke(delegate, args);
                    boolean isTrigger = sql != null && sql.startsWith(triggerSqlPrefix);
                    return wrapStatement(real, isTrigger);
                case "rollback":
                    Object result = method.invoke(delegate, args);
                    poisoned.set(false);
                    return result;
                default:
                    return method.invoke(delegate, args);
            }
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private PreparedStatement wrapStatement(PreparedStatement real, boolean isTrigger) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> {
                    try {
                        if (!"executeUpdate".equals(method.getName())) {
                            return method.invoke(real, args);
                        }
                        if (poisoned.get()) {
                            throw new SQLException(
                                    "ERROR: current transaction is aborted, commands ignored until end of"
                                            + " transaction block",
                                    IN_FAILED_TRANSACTION_SQLSTATE);
                        }
                        Object result;
                        try {
                            result = method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            if (e.getCause() instanceof SQLException) {
                                poisoned.set(true);
                            }
                            throw e.getCause();
                        }
                        if (isTrigger && triggerFired.compareAndSet(false, true)) {
                            afterTriggerStatementExecutes.run();
                        }
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
