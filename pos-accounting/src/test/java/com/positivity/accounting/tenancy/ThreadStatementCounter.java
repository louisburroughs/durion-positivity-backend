package com.positivity.accounting.tenancy;

import java.util.concurrent.Callable;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Counts the SQL statements Hibernate prepares on the calling thread while a count is open (#2502,
 * AC11). Registered for the {@code pg} profile through {@code
 * hibernate.session_factory.statement_inspector}; it never changes a statement and counts nothing
 * unless a test opens a count, so other tests are unaffected.
 *
 * <p>Factory-wide Hibernate statistics also see the statements of scheduled jobs running on other
 * threads (the outbox processor polls every few seconds), which made a bounded-query assertion
 * flaky; this counter sees only the thread under test.
 */
public class ThreadStatementCounter implements StatementInspector {

    private static final ThreadLocal<int[]> COUNT = new ThreadLocal<>();

    /** Run {@code work} on this thread and return how many statements Hibernate prepared for it. */
    public static <T> Counted<T> count(Callable<T> work) throws Exception {
        int[] count = new int[1];
        COUNT.set(count);
        try {
            T result = work.call();
            return new Counted<>(result, count[0]);
        } finally {
            COUNT.remove();
        }
    }

    @Override
    public String inspect(String sql) {
        int[] count = COUNT.get();
        if (count != null) {
            count[0]++;
        }
        return sql;
    }

    /** A result and the number of statements issued to produce it. */
    public record Counted<T>(T result, int statements) {}
}
