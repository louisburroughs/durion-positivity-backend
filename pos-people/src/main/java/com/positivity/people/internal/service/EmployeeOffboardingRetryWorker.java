package com.positivity.people.internal.service;

import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.EmployeeOffboardingRetryRepository;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.tenancy.TenantIterator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains {@code employee_offboarding_retry_queue} (#2121). {@link OffboardingEventListener} queues a row
 * when applying the assignment policy fails; this worker re-applies the policy through the same
 * {@link OffboardingAssignmentEnder} until it succeeds, then deletes the row. It also finishes
 * offboarding: it ends the assignments of DISABLED or TERMINATED employees that are past their
 * {@code effectiveTo} (a GRACE_PERIOD that ran out) or still open-ended (an IMMEDIATE the
 * after-commit handler never got to because the process died), publishing each.
 *
 * <p>Per tenant (ADR-0062 §3): the queue table is under row-level security, so the sweep runs
 * inside {@link TenantIterator#forEachActiveTenant}. Each row is worked in its own
 * {@code REQUIRES_NEW} transaction, and a failure is recorded in a second one, because the failed
 * transaction is rollback-only and cannot also carry the attempt count. A row that keeps failing
 * backs off exponentially (five minutes doubling per attempt, capped at a day) and, at
 * {@code pos.people.offboarding-retry.max-attempts}, is left in the table for an operator with an
 * error logged; it is not picked up again.
 *
 * <p>There is no scheduler lock: two instances may work the same row at once. That is safe because
 * applying a policy is idempotent and each row is re-read inside its own transaction, but the
 * second instance may repeat work and republish nothing new.
 *
 * <p>Disable with {@code pos.people.offboarding-retry.enabled=false}.
 */
@Component
@ConditionalOnProperty(
        prefix = "pos.people.offboarding-retry",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@Slf4j
public class EmployeeOffboardingRetryWorker {

    /** Delay before the first retry, and the base of the exponential backoff. */
    static final long BASE_DELAY_SECONDS = 300;

    static final long MAX_DELAY_SECONDS = 86_400;

    /** {@code failure_reason} is a varchar(255) column. */
    private static final int FAILURE_REASON_MAX_LENGTH = 255;

    private final EmployeeOffboardingRetryRepository retryRepository;
    private final EmployeeRepository employeeRepository;
    private final OffboardingAssignmentEnder assignmentEnder;
    private final TenantIterator tenantIterator;
    private final Clock clock;
    private final TransactionTemplate requiresNew;
    private final int maxAttempts;

    public EmployeeOffboardingRetryWorker(
            EmployeeOffboardingRetryRepository retryRepository,
            EmployeeRepository employeeRepository,
            OffboardingAssignmentEnder assignmentEnder,
            TenantIterator tenantIterator,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${pos.people.offboarding-retry.max-attempts:10}") int maxAttempts) {
        this.retryRepository = retryRepository;
        this.employeeRepository = employeeRepository;
        this.assignmentEnder = assignmentEnder;
        this.tenantIterator = tenantIterator;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${pos.people.offboarding-retry.interval:PT60S}")
    public void runScheduledSweep() {
        tenantIterator.forEachActiveTenant(tenantId -> sweepTenant());
    }

    /** One pass for the tenant bound on the calling thread. */
    void sweepTenant() {
        List<EmployeeOffboardingRetry> due =
                retryRepository.findByNextAttemptAtLessThanEqualAndAttemptsLessThanOrderByNextAttemptAtAsc(
                        Instant.now(clock), maxAttempts);
        for (EmployeeOffboardingRetry row : due) {
            retry(row.getId());
        }
        try {
            requiresNew.executeWithoutResult(status -> assignmentEnder.endLingeringAssignments());
        } catch (RuntimeException e) {
            // Next pass retries; the sweep is a pure function of the data.
            log.warn("Ending lingering staffing assignments of offboarded employees failed: {}", e.getMessage());
        }
    }

    private void retry(UUID retryId) {
        try {
            requiresNew.executeWithoutResult(status -> process(retryId));
        } catch (RuntimeException e) {
            log.warn("Offboarding retry {} failed: {}", retryId, e.getMessage());
            try {
                requiresNew.executeWithoutResult(status -> recordFailure(retryId, e));
            } catch (RuntimeException recordingFailure) {
                // The row keeps its old attempts and nextAttemptAt, so the next pass tries again.
                log.warn("Could not record failure of offboarding retry {}", retryId, recordingFailure);
            }
        }
    }

    /** Re-read the row (another instance may have finished it) and apply its policy. */
    private void process(UUID retryId) {
        EmployeeOffboardingRetry row = retryRepository.findById(retryId).orElse(null);
        if (row == null || !isDue(row)) {
            return;
        }
        Employee employee =
                employeeRepository.findByPersonId(row.getEmployeeId()).orElse(null);
        if (employee == null || employee.getStatus() == EmployeeStatus.ACTIVE) {
            // Re-enabled (or gone) since the disable: ending assignments now would undo the re-enable.
            log.info(
                    "Dropping offboarding retry {} for employee {}: no longer offboarded",
                    retryId,
                    row.getEmployeeId());
            retryRepository.delete(row);
            return;
        }
        assignmentEnder.apply(
                row.getEmployeeId(), row.getAssignmentPolicy(), row.getAssignmentEndDate(), row.getActorId());
        retryRepository.delete(row);
        log.info("Offboarding retry {} for employee {} succeeded", retryId, row.getEmployeeId());
    }

    private boolean isDue(EmployeeOffboardingRetry row) {
        return row.getAttempts() < maxAttempts && !row.getNextAttemptAt().isAfter(Instant.now(clock));
    }

    private void recordFailure(UUID retryId, RuntimeException failure) {
        EmployeeOffboardingRetry row = retryRepository.findById(retryId).orElse(null);
        if (row == null) {
            return;
        }
        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        row.setFailureReason(failureReason(failure.getMessage()));
        row.setNextAttemptAt(Instant.now(clock).plusSeconds(backoffSeconds(attempts)));
        retryRepository.save(row);
        if (attempts >= maxAttempts) {
            log.error(
                    "Offboarding retry {} for employee {} gave up after {} attempts; assignments still need"
                            + " ending by an operator. Last failure: {}",
                    retryId,
                    row.getEmployeeId(),
                    attempts,
                    row.getFailureReason());
        }
    }

    /** Five minutes doubling per attempt, capped at a day. */
    static long backoffSeconds(int attempts) {
        int shift = Math.min(Math.max(attempts, 0), 20);
        return Math.min(BASE_DELAY_SECONDS << shift, MAX_DELAY_SECONDS);
    }

    static @NonNull String failureReason(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        return message.length() > FAILURE_REASON_MAX_LENGTH ? message.substring(0, FAILURE_REASON_MAX_LENGTH) : message;
    }
}
