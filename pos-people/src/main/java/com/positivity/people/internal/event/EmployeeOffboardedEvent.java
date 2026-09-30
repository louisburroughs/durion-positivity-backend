package com.positivity.people.internal.event;

import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * In-process signal that an employee was disabled and their staffing assignments now need the
 * requested termination policy applied (#2121). Published by {@code disableEmployee} inside its
 * transaction and handled after that transaction commits, so a failure applying the policy cannot
 * roll the disable back or take the retry-queue write down with it.
 *
 * @param personId the employee's person id (the API's employee id)
 * @param policy how the assignments are terminated
 * @param assignmentEndDate the GRACE_PERIOD end date; null otherwise
 * @param disableReason the reason given for the disable, carried onto a queued retry
 * @param actorId who disabled the employee
 */
public record EmployeeOffboardedEvent(
        @NonNull UUID personId,
        @NonNull AssignmentTerminationPolicy policy,
        @Nullable LocalDate assignmentEndDate,
        @Nullable String disableReason,
        @NonNull String actorId) {}
