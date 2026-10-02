package com.positivity.people.internal.event;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * In-process signal that an employee was offboarded and their staffing assignments now need a
 * termination policy applied (#2121). Published by {@code disableEmployee}, with the policy the
 * request asked for, and by {@code updateEmployee} when it moves the status into TERMINATED or
 * DISABLED, always with IMMEDIATE (#2361). It is published inside that transaction and handled
 * after the transaction commits, so a failure applying the policy cannot roll the status change
 * back.
 *
 * <p>The policy itself is not carried here: the publisher writes it to an
 * {@code employee_offboarding_retry_queue} row in the same transaction (#2360), and that row is
 * what the handler, and the retry worker if the handler does not finish, apply.
 *
 * @param personId the employee's person id (the API's employee id)
 * @param retryId the queue row that carries the policy, its end date and the actor
 */
public record EmployeeOffboardedEvent(
        @NonNull UUID personId, @NonNull UUID retryId) {}
