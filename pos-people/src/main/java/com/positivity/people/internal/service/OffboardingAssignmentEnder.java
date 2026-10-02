package com.positivity.people.internal.service;

import com.positivity.people.internal.config.PeopleEventPublisher;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Applies an {@link AssignmentTerminationPolicy} to a person's staffing assignments when the
 * employee is offboarded (#2121). Shared by {@link OffboardingEventListener} and the
 * {@link EmployeeOffboardingRetryWorker}, so a retry does exactly what the original request would
 * have done.
 *
 * <p>It goes to the repository and the event publisher directly rather than through {@code
 * StaffingAssignmentService#end}: that method enforces the caller's location reach, and an
 * administrator who may disable an employee need not hold {@code EMPLOYEE_EDIT} at every site the
 * employee is staffed at.
 *
 * <p>No method here opens a transaction: each joins the caller's. {@link PeopleEventPublisher}
 * writes the outbox row in that same transaction (ADR-0044 §4), so an assignment change and its
 * fact commit or roll back together.
 *
 * <p>Both operations are idempotent: an assignment already in the state a policy asks for is left
 * alone and publishes nothing, so a retry after a partial failure, or two instances racing on the
 * same queue row, converge on the same result.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OffboardingAssignmentEnder {

    /** Employee statuses whose leftover assignments the worker sweep ends. */
    static final Set<EmployeeStatus> OFFBOARDED_STATUSES = Set.of(EmployeeStatus.DISABLED, EmployeeStatus.TERMINATED);

    /**
     * How long after an employee's status change the open-ended sweep leaves their assignments
     * alone; matches the retry queue's first delay. An offboarding is already kept from the sweep
     * by its queue row, so this only delays an employee whose row has used up its attempts.
     */
    static final long SETTLE_SECONDS = 300;

    private final Clock clock;

    private final EmployeeLocationAssignmentRepository assignmentRepository;

    private final PeopleEventPublisher peopleEventPublisher;

    /**
     * Apply {@code policy} to every ACTIVE assignment of the person.
     *
     * <ul>
     *   <li>{@code IMMEDIATE}: status becomes ENDED and {@code effectiveTo} today, unless it already
     *       ends earlier.
     *   <li>{@code GRACE_PERIOD}: {@code effectiveTo} becomes {@code assignmentEndDate} (unless it
     *       already ends on or before it) and the assignment stays ACTIVE until that date has
     *       passed; the retry worker's sweep ({@link #findLingeringAssignmentIds(int)}) then ends
     *       it.
     * </ul>
     *
     * An assignment that has not started yet and would start after the cut-off date can never be
     * worked, so it is ended outright with {@code effectiveTo} equal to its start.
     *
     * @return how many assignments were changed (and published)
     * @throws IllegalStateException when GRACE_PERIOD is asked for without an end date
     */
    public int apply(
            @NonNull UUID personId,
            @NonNull AssignmentTerminationPolicy policy,
            @Nullable LocalDate assignmentEndDate,
            @NonNull String actorId) {
        LocalDate today = LocalDate.now(clock);
        LocalDate cutoff;
        switch (policy) {
            case IMMEDIATE -> cutoff = today;
            case GRACE_PERIOD -> {
                if (assignmentEndDate == null) {
                    throw new IllegalStateException("GRACE_PERIOD requires an assignmentEndDate");
                }
                cutoff = assignmentEndDate;
            }
            default -> throw new IllegalStateException("Unsupported assignment policy");
        }

        int changed = 0;
        for (EmployeeLocationAssignment assignment : assignmentRepository.findByEmployee_PersonId(personId)) {
            if (assignment.getStatus() != AssignmentStatus.ACTIVE) {
                continue;
            }
            if (endAt(assignment, cutoff, policy == AssignmentTerminationPolicy.IMMEDIATE)) {
                peopleEventPublisher.publishStaffingAssignmentUpdated(assignmentRepository.save(assignment));
                changed++;
            }
        }
        log.info(
                "Applied {} assignment offboarding for person {} by actor {}: {} assignment(s) changed",
                policy,
                personId,
                actorId,
                changed);
        return changed;
    }

    /**
     * Assignments that an offboarding should already have ended and nothing else will: ACTIVE
     * assignments of DISABLED or TERMINATED employees that are past their {@code effectiveTo} (a
     * GRACE_PERIOD that has run out; nothing flips the status when the date passes, and
     * status-keyed consumers such as pos-shop-manager's mechanic projection would keep treating the
     * person as staffed), or open-ended ones no queue row will get to: those of an employee whose
     * retry row gave up. An offboarding's own assignments are not among them while its row is
     * pending (#2360), whether it came from {@code disableEmployee} or from a status moved into
     * TERMINATED or DISABLED through {@code updateEmployee} (#2361). The caller ends each through
     * {@link #endLingeringAssignment(UUID)} in a transaction of its own, so one bad row cannot roll
     * back the rest.
     *
     * @param maxAttempts the retry worker's attempt cap; a retry row at or past it no longer counts
     *     as pending
     * @return the assignment ids, in a stable order
     */
    public @NonNull List<UUID> findLingeringAssignmentIds(int maxAttempts) {
        LocalDate today = LocalDate.now(clock);
        Instant settledBefore = Instant.now(clock).minusSeconds(SETTLE_SECONDS);
        return assignmentRepository
                .findOpenForOffboardedEmployees(today, settledBefore, OFFBOARDED_STATUSES, maxAttempts)
                .stream()
                .map(EmployeeLocationAssignment::getId)
                .toList();
    }

    /**
     * End one assignment found by {@link #findLingeringAssignmentIds(int)}: it becomes ENDED, an
     * open end date becoming today, and it is published. The row is re-read here, inside the
     * caller's transaction, so an assignment another instance already ended, or whose employee was
     * re-enabled in the meantime, is left alone.
     *
     * @return whether the assignment was ended (and published)
     */
    public boolean endLingeringAssignment(@NonNull UUID assignmentId) {
        EmployeeLocationAssignment assignment =
                assignmentRepository.findById(assignmentId).orElse(null);
        if (assignment == null || assignment.getStatus() != AssignmentStatus.ACTIVE) {
            return false;
        }
        EmployeeStatus employeeStatus = assignment.getEmployee().getStatus();
        if (employeeStatus == null || !OFFBOARDED_STATUSES.contains(employeeStatus)) {
            return false;
        }
        endAt(assignment, LocalDate.now(clock), true);
        peopleEventPublisher.publishStaffingAssignmentUpdated(assignmentRepository.save(assignment));
        log.info(
                "Ended staffing assignment {} of offboarded employee {} left open by an offboarding",
                assignmentId,
                assignment.getEmployee().getPersonId());
        return true;
    }

    /**
     * Cap an ACTIVE assignment at {@code cutoff}.
     *
     * @param endNow whether the assignment ends now (IMMEDIATE) rather than staying ACTIVE until its
     *     new {@code effectiveTo} has passed
     * @return whether anything changed
     */
    private boolean endAt(EmployeeLocationAssignment assignment, LocalDate cutoff, boolean endNow) {
        boolean changed = false;
        LocalDate from = assignment.getEffectiveFrom();
        LocalDate to = assignment.getEffectiveTo();
        boolean neverStarts = from.isAfter(cutoff);
        LocalDate newTo = neverStarts ? from : cutoff;
        if (to == null || to.isAfter(newTo)) {
            assignment.setEffectiveTo(newTo);
            changed = true;
        }
        if (endNow || neverStarts) {
            assignment.setStatus(AssignmentStatus.ENDED);
            changed = true;
        }
        return changed;
    }
}
