package com.positivity.tenancy.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.HeuristicCompletionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.InvalidTimeoutException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.TransactionSuspensionNotSupportedException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.UnexpectedRollbackException;

/** Pins the ADR-0044 §4 consumer rethrow set (amended 2026-10-02, #2355). */
class RetryableConsumerFailuresTest {

    static Stream<Throwable> retryable() {
        return Stream.of(
                // TransientDataAccessException: what consumers already rethrew before #2355.
                new QueryTimeoutException("statement timeout"),
                new CannotAcquireLockException("lock timeout"),
                new OptimisticLockingFailureException("stale row"),
                // The gap #2355 closes: Spring classes a lost connection as non-transient.
                new DataAccessResourceFailureException("connection reset"),
                new CannotGetJdbcConnectionException("pool exhausted"),
                new RecoverableDataAccessException("driver says retry"),
                // Not DataAccessExceptions at all. The three TransactionException types in the set:
                // the REQUIRES_NEW transaction never opened, the commit failed in the
                // infrastructure, the transaction ran past its deadline.
                new CannotCreateTransactionException("could not open JPA EntityManager for transaction"),
                new TransactionSystemException("could not commit JPA transaction"),
                new TransactionTimedOutException("transaction timed out: deadline was 12:00:05"),
                // The same three, wrapped by a service-level exception.
                new IllegalStateException(
                        "could not apply event", new CannotCreateTransactionException("could not open")),
                new IllegalStateException("could not apply event", new TransactionSystemException("could not commit")),
                new IllegalStateException(
                        "could not apply event", new TransactionTimedOutException("transaction timed out")));
    }

    static Stream<Throwable> permanent() {
        return Stream.of(
                new DataIntegrityViolationException("duplicate key"),
                new InvalidDataAccessApiUsageException("detached entity"),
                new IllegalArgumentException("malformed payload"),
                new IllegalStateException("business rejection"),
                new NullPointerException(),
                // Every other TransactionException: a state the same code reaches again on
                // redelivery, so retrying only holds the partition and dead-letters.
                new UnexpectedRollbackException("transaction silently rolled back: marked rollback-only"),
                new IllegalTransactionStateException("no existing transaction found for 'mandatory'"),
                new NoTransactionException("no transaction aspect-managed TransactionStatus in scope"),
                new InvalidTimeoutException("invalid transaction timeout", -5),
                new HeuristicCompletionException(HeuristicCompletionException.STATE_MIXED, null),
                new IllegalStateException(
                        "could not apply event", new UnexpectedRollbackException("marked rollback-only")),
                // Subclasses of CannotCreateTransactionException that describe the transaction
                // manager, not the database: excluded by name.
                new NestedTransactionNotSupportedException("transaction manager does not allow nested transactions"),
                new TransactionSuspensionNotSupportedException("transaction manager does not support suspension"),
                // An untranslated driver failure carries no Spring type, so it is not in the set.
                new IllegalStateException("wrapped", new SQLException("connection reset", "08006")),
                new Exception("checked and unrelated"));
    }

    @ParameterizedTest
    @MethodSource("retryable")
    void aFailureInTheRetryableSetIsRetryable(Throwable failure) {
        assertThat(RetryableConsumerFailures.isRetryable(failure)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("permanent")
    void anyOtherFailureIsPermanent(Throwable failure) {
        assertThat(RetryableConsumerFailures.isRetryable(failure)).isFalse();
    }

    @Test
    void aRetryableFailureWrappedByAServiceExceptionIsStillRetryable() {
        Throwable wrapped = new IllegalStateException(
                "could not apply event", new DataAccessResourceFailureException("connection reset"));

        assertThat(RetryableConsumerFailures.isRetryable(wrapped)).isTrue();
    }

    @Test
    void aRetryableFailureDeepInTheCauseChainIsStillRetryable() {
        Throwable wrapped = new RuntimeException(
                "outer", new IllegalStateException("middle", new CannotCreateTransactionException("could not open")));

        assertThat(RetryableConsumerFailures.isRetryable(wrapped)).isTrue();
    }

    @Test
    void aPermanentFailureWithAPermanentCauseStaysPermanent() {
        Throwable wrapped = new IllegalStateException("rejected", new DataIntegrityViolationException("duplicate key"));

        assertThat(RetryableConsumerFailures.isRetryable(wrapped)).isFalse();
    }

    @Test
    void anExcludedSubtypeDoesNotHideARetryableCause() {
        // Each link is judged on its own: the manager's refusal is permanent, the lost connection
        // underneath it is not.
        Throwable wrapped = new NestedTransactionNotSupportedException(
                "could not create savepoint", new DataAccessResourceFailureException("connection reset"));

        assertThat(RetryableConsumerFailures.isRetryable(wrapped)).isTrue();
    }

    @Test
    void aCyclicCauseChainTerminates() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(RetryableConsumerFailures.isRetryable(first)).isFalse();
    }
}
