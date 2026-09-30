package com.positivity.people.internal.service;

import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import com.positivity.people.internal.event.EmployeeOffboardedEvent;
import com.positivity.people.internal.repository.EmployeeOffboardingRetryRepository;
import java.time.Clock;
import java.time.Instant;
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
 * (#2121), and queues a retry when that fails.
 *
 * <p>Why after commit: a Spring Data call that throws inside {@code disableEmployee}'s own
 * transaction marks it rollback-only, and the retry row written in that same transaction would
 * roll back with it, on exactly the failure the queue exists for. Here the disable is already
 * durable, the policy runs in its own {@code REQUIRES_NEW} transaction, and a failure is caught
 * and queued through a second {@code REQUIRES_NEW} transaction that commits regardless. Nothing
 * thrown here reaches the caller: the disable is durable by now, and an exception out of an
 * after-commit callback would report it as a 500.
 *
 * <p>Both transactions are explicit {@link TransactionTemplate}s rather than
 * {@code @Transactional} on this method: a {@code @Transactional} proxy around a method that
 * swallows the exception still tries to commit a rollback-only transaction and throws {@code
 * UnexpectedRollbackException} out of the listener. The handler runs on the request thread inside
 * the committing transaction's after-commit callback, so the request's tenant binding still
 * holds for both connections.
 *
 * <p>A crash between the commit and this handler leaves no retry row; the
 * {@link EmployeeOffboardingRetryWorker} sweep ends the assignments of any DISABLED or TERMINATED
 * employee that are still open, which covers it.
 */
@Component
@Slf4j
public class OffboardingEventListener {

    private final OffboardingAssignmentEnder assignmentEnder;
    private final EmployeeOffboardingRetryRepository retryRepository;
    private final Clock clock;
    private final TransactionTemplate requiresNew;

    public OffboardingEventListener(
            OffboardingAssignmentEnder assignmentEnder,
            EmployeeOffboardingRetryRepository retryRepository,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.assignmentEnder = assignmentEnder;
        this.retryRepository = retryRepository;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmployeeOffboarded(@NonNull EmployeeOffboardedEvent event) {
        try {
            requiresNew.executeWithoutResult(status -> assignmentEnder.apply(
                    event.personId(), event.policy(), event.assignmentEndDate(), event.actorId()));
        } catch (RuntimeException exception) {
            log.warn(
                    "Offboarding downstream action failed for employee {}. Queuing retry. Reason: {}",
                    event.personId(),
                    exception.getMessage());
            queueRetry(event, exception.getMessage());
        }
    }

    private void queueRetry(EmployeeOffboardedEvent event, String failureReason) {
        EmployeeOffboardingRetry retry = new EmployeeOffboardingRetry();
        retry.setEmployeeId(event.personId());
        retry.setAssignmentPolicy(event.policy());
        retry.setAssignmentEndDate(event.assignmentEndDate());
        retry.setDisableReason(event.disableReason());
        retry.setActorId(event.actorId());
        retry.setFailureReason(EmployeeOffboardingRetryWorker.failureReason(failureReason));
        retry.setAttempts(0);
        retry.setNextAttemptAt(Instant.now(clock).plusSeconds(EmployeeOffboardingRetryWorker.BASE_DELAY_SECONDS));
        try {
            requiresNew.executeWithoutResult(status -> retryRepository.save(retry));
        } catch (RuntimeException queueFailure) {
            // Swallowed on purpose: the disable is already committed, and an exception out of an
            // after-commit callback would turn that committed disable into a 500 for the caller.
            // Without a queue row the worker's sweep of still-open assignments of offboarded
            // employees converges on the same result (at today's date rather than a grace date).
            log.error(
                    "Offboarding retry for employee {} (policy {}) could not be queued after the downstream"
                            + " action failed ({}); the sweep of open assignments will end them instead",
                    event.personId(),
                    event.policy(),
                    failureReason,
                    queueFailure);
        }
    }
}
