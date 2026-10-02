package com.positivity.tenancy.kafka;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * The one definition of which failures a Kafka consumer rethrows for container retry (ADR-0044 §4,
 * as amended 2026-10-02 by durion-positivity-backend#2355).
 *
 * <p>A consumer that catches a handler failure asks {@link #isRetryable(Throwable)} first and
 * rethrows on {@code true}, before it logs, records or marks anything. The container's error
 * handler then retries the record with exponential backoff and publishes it to {@code {topic}.dlq}
 * when the retries are exhausted. Everything else is a permanent failure: redelivery cannot fix it,
 * so the consumer logs it and, where it records failures, marks the record processed.
 *
 * <p>The retryable set is:
 *
 * <ul>
 *   <li>{@link TransientDataAccessException}: a lock timeout, a deadlock, a query timeout, an
 *       optimistic or pessimistic locking failure.
 *   <li>{@link RecoverableDataAccessException}: a failure the driver reports as recoverable once
 *       the transaction is retried on a fresh connection.
 *   <li>{@link DataAccessResourceFailureException}: a dropped or refused database connection
 *       (PgJDBC {@code 08xxx}, Hibernate's {@code JDBCConnectionException}, an exhausted pool).
 *       Spring files it under {@code NonTransientDataAccessResourceException}, where
 *       "non-transient" means "retrying at once will not help". The container's backoff is exactly
 *       the answer to that.
 *   <li>{@link TransactionException}: a failure of the transaction infrastructure itself, such as
 *       {@code CannotCreateTransactionException} when the {@code REQUIRES_NEW} transaction cannot
 *       be opened, or {@code TransactionSystemException} when the commit fails.
 * </ul>
 *
 * <p>The whole cause chain is inspected, not only the exception that was thrown. A service that
 * wraps a lost connection in its own exception has still lost the connection, and marking that
 * record processed loses the event just the same. A failure with one of the types above anywhere in
 * its causes is therefore retryable. The cost of being wrong in that direction is bounded: a
 * handful of retries and a dead letter somebody looks at, against an event that silently never
 * applies.
 *
 * <p>Only these Spring types count. A raw driver or Hibernate exception that reaches the consumer
 * untranslated (no Spring type anywhere in its chain) is not in the set and stays permanent.
 */
public final class RetryableConsumerFailures {

    /** The retryable set. Changing ADR-0044 §4 means changing this list and nothing else. */
    private static final List<Class<? extends Throwable>> RETRYABLE_TYPES = List.of(
            TransientDataAccessException.class,
            RecoverableDataAccessException.class,
            DataAccessResourceFailureException.class,
            TransactionException.class);

    private RetryableConsumerFailures() {}

    /**
     * Whether a consumer must rethrow {@code failure} for container retry instead of handling it as
     * permanent.
     *
     * @param failure the exception the consumer caught
     * @return {@code true} if {@code failure}, or any exception in its cause chain, is one of the
     *     retryable types
     */
    public static boolean isRetryable(@NonNull Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable link = failure; link != null && seen.add(link); link = link.getCause()) {
            if (isRetryableType(link)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRetryableType(Throwable link) {
        for (Class<? extends Throwable> type : RETRYABLE_TYPES) {
            if (type.isInstance(link)) {
                return true;
            }
        }
        return false;
    }
}
