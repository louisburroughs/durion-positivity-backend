package com.positivity.domainevents.people;

import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code people.staffing-assignment.updated} v1 on {@code people.events.v1}
 * (ADR-0044 §6, #875).
 *
 * <p>Published by pos-people after a person-to-location staffing assignment is created, updated,
 * or ended. Downstream schedulers (pos-workorder, pos-shop-manager) maintain assignment replicas
 * from these facts instead of calling pos-people synchronously (Phase 3.4, #877).
 *
 * <p>The envelope's {@code aggregateVersion} is the emission timestamp in epoch milliseconds
 * (last-writer-wins ordering hint per assignmentId; compare with {@code >=} when guarding).
 */
public record StaffingAssignmentUpdatedV1(
        @NonNull UUID assignmentId,
        @NonNull UUID employeeId,
        @NonNull UUID personId,
        @NonNull UUID locationId,
        @Nullable String role,
        boolean primary,
        @NonNull String status,
        @Nullable LocalDate effectiveFrom,
        @Nullable LocalDate effectiveTo) {

    public static final String EVENT_TYPE = "people.staffing-assignment.updated";
    public static final int SCHEMA_VERSION = 1;
}
