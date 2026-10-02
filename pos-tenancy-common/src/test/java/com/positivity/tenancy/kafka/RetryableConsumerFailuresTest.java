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
import org.springframework.transaction.TransactionSystemException;

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
                // Not a DataAccessException at all: the REQUIRES_NEW transaction never opened.
                new CannotCreateTransactionException("could not open JPA EntityManager for transaction"),
                new TransactionSystemException("could not commit JPA transaction"));
    }

    static Stream<Throwable> permanent() {
        return Stream.of(
                new DataIntegrityViolationException("duplicate key"),
                new InvalidDataAccessApiUsageException("detached entity"),
                new IllegalArgumentException("malformed payload"),
                new IllegalStateException("business rejection"),
                new NullPointerException(),
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
    void aCyclicCauseChainTerminates() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(RetryableConsumerFailures.isRetryable(first)).isFalse();
    }
}
