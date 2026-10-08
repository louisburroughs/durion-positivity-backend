package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.DatabaseDialectSupport;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounds how long an AP command waits for a row lock (CAP:550 S42, #2627). The AP pay command locks its bills before
 * the gateway call and holds them across it; approve, reject, void and the due-date change lock a
 * bill. Each runs {@link #apply} first, so a wait beyond {@code accounting.ap.lock-timeout} (default 5 s) fails with
 * SQLSTATE 55P03 instead of queueing behind a slow gateway: the transaction rolls back and the caller gets 409 {@code
 * LOCK_TIMEOUT} and retries. Work committed in a REQUIRES_NEW transaction (an audit row of a refusal) does not inherit
 * the limit and is not rolled back.
 *
 * <p>{@code set_config(..., true)} is {@code SET LOCAL}: the limit lasts until the transaction ends and never leaks to
 * the next borrower of the pooled connection. The value is bound as a parameter, never spliced into SQL. On H2 (the dev
 * profile and the slice tests) there is no such setting and nothing is done.
 *
 * <p>If {@code idle_in_transaction_session_timeout} is ever set for pos-accounting, it must exceed the gateway's
 * connect + read timeouts, or a slow gateway call would end the session that holds the locks.
 */
@Component
public class ApLockTimeout {

    private final EntityManager entityManager;
    private final DatabaseDialectSupport dialect;
    private final Duration timeout;

    public ApLockTimeout(
            EntityManager entityManager,
            DatabaseDialectSupport dialect,
            @Value("${accounting.ap.lock-timeout:5s}") Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalStateException("accounting.ap.lock-timeout must be a positive duration, was " + timeout);
        }
        this.entityManager = entityManager;
        this.dialect = dialect;
        this.timeout = timeout;
    }

    /** Sets the transaction's lock timeout; call before the first row lock of the command. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply() {
        if (!dialect.isPostgreSql()) {
            return;
        }
        entityManager
                .createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)")
                .setParameter("timeout", timeout.toMillis() + "ms")
                .getSingleResult();
    }

    /** The configured limit. */
    public @NonNull Duration timeout() {
        return timeout;
    }
}
