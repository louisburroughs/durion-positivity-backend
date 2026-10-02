package com.positivity.people.internal.event;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * In-process signal that an employee was disabled and their staffing assignments now need the
 * requested termination policy applied (#2121). Published by {@code disableEmployee} inside its
 * transaction and handled after that transaction commits, so a failure applying the policy cannot
 * roll the disable back.
 *
 * <p>The policy itself is not carried here: {@code disableEmployee} writes it to an
 * {@code employee_offboarding_retry_queue} row in the same transaction (#2360), and that row is
 * what the handler, and the retry worker if the handler does not finish, apply.
 *
 * @param personId the employee's person id (the API's employee id)
 * @param retryId the queue row that carries the policy, its end date and the actor
 */
public record EmployeeOffboardedEvent(
        @NonNull UUID personId, @NonNull UUID retryId) {}
