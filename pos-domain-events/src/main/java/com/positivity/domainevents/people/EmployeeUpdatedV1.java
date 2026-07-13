package com.positivity.domainevents.people;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code people.employee.updated} v1 on {@code people.events.v1} (ADR-0044 §6, #875).
 *
 * <p>Published by pos-people after every employment mutation (create, update, disable). Carries
 * the employment facts pos-people owns; identity attributes (names, contacts) are owned by
 * pos-people-contact and travel on {@code people-contact.events.v1} instead — consumers join the
 * two replicas on {@code personId}.
 *
 * <p>The envelope's {@code aggregateVersion} is the emission timestamp in epoch milliseconds
 * (last-writer-wins ordering hint per employeeId; compare with {@code >=} when guarding).
 */
public record EmployeeUpdatedV1(
        @NonNull UUID employeeId,
        @NonNull UUID personId,
        @Nullable String employeeNumber,
        @NonNull String status,
        @Nullable LocalDate hireDate,
        @Nullable LocalDate terminationDate,
        @Nullable Instant statusEffectiveAt) {

    public static final String EVENT_TYPE = "people.employee.updated";
    public static final int SCHEMA_VERSION = 1;
}
