package com.positivity.people.internal.service;

import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import com.positivity.people.internal.event.EmployeeOffboardedEvent;
import com.positivity.people.internal.repository.EmployeeOffboardingRetryRepository;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies an employee's assignment termination policy once {@code disableEmployee} has committed
 * (#2121), and settles the queue row that disable wrote for it (#2360).
 *
 * <p>Why after commit: a Spring Data call that throws inside {@code disableEmployee}'s own
 * transaction marks it rollback-only, and applying the policy must not be able to undo the disable.
 * The policy is durable before this handler runs: {@code disableEmployee} inserts the
 * {@code employee_offboarding_retry_queue} row in its own transaction, so the row exists whenever
 * the disable does. Here the policy is applied and that row deleted in one {@code REQUIRES_NEW}
 * transaction: both commit, or neither does and the row stays for the
 * {@link EmployeeOffboardingRetryWorker}, which applies the same policy and end date from it. That
 * holds on every way of not finishing: a failure applying the policy, a failure committing, or the
 * process dying before this handler ran at all. There is nothing to write on failure, so nothing
 * that can fail to be written.
 *
 * <p>A failure here is not counted as an attempt: the row keeps {@code attempts = 0} and the first
 * due time {@code disableEmployee} gave it, which is what the worker's backoff and attempt cap
 * count from. Nothing thrown here reaches the caller: the disable is durable by now, and an
 * exception out of an after-commit callback would report it as a 500.
 *
 * <p>The transaction is an explicit {@link TransactionTemplate} rather than {@code @Transactional}
 * on this method: a {@code @Transactional} proxy around a method that swallows the exception still
 * tries to commit a rollback-only transaction and throws {@code UnexpectedRollbackException} out of
 * the listener. The handler runs on the request thread inside the committing transaction's
 * after-commit callback, so the request's tenant binding still holds for its connection.
 *
 * <p>The worker does not race this handler in the ordinary case: the row is not due until the
 * worker's first delay has passed. Should the handler be held up for longer than that, the two are
 * serialized by the row itself: each reads it with a row lock before applying it, so the second
 * waits for the first to finish and then finds the row deleted and does nothing.
 *
 * <p>An offboarding through {@code updateEmployee} (a status moved into TERMINATED or DISABLED,
 * #2361) arrives here the same way: that method writes the same row, always with the IMMEDIATE
 * policy, and publishes the same event, so everything said of the disable above holds for it.
 */
@Component
@Slf4j
public class OffboardingEventListener {

    private final OffboardingAssignmentEnder assignmentEnder;
    private final EmployeeOffboardingRetryRepository retryRepository;
    private final TransactionTemplate requiresNew;

    public OffboardingEventListener(
            OffboardingAssignmentEnder assignmentEnder,
            EmployeeOffboardingRetryRepository retryRepository,
            PlatformTransactionManager transactionManager) {
        this.assignmentEnder = assignmentEnder;
        this.retryRepository = retryRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmployeeOffboarded(@NonNull EmployeeOffboardedEvent event) {
        try {
            requiresNew.executeWithoutResult(status -> applyAndSettle(event));
        } catch (RuntimeException exception) {
            // Swallowed on purpose: the disable is already committed, and an exception out of an
            // after-commit callback would turn that committed disable into a 500 for the caller.
            // The queue row committed with the disable and this transaction's delete rolled back
            // with the rest, so the worker picks the row up with the request's own policy.
            log.warn(
                    "Offboarding downstream action failed for employee {}; retry {} stays queued for the"
                            + " worker. Reason: {}",
                    event.personId(),
                    event.retryId(),
                    exception.getMessage());
        }
    }

    /** Claim the row (the worker may hold or have settled it), apply its policy and delete it. */
    private void applyAndSettle(EmployeeOffboardedEvent event) {
        EmployeeOffboardingRetry row =
                retryRepository.findByIdForUpdate(event.retryId()).orElse(null);
        if (row == null) {
            return;
        }
        assignmentEnder.apply(
                row.getEmployeeId(), row.getAssignmentPolicy(), row.getAssignmentEndDate(), row.getActorId());
        retryRepository.delete(row);
    }
}
