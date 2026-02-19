package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.AssignmentStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StaffingAssignmentResponse(
        UUID assignmentId,
        UUID personId,
        UUID locationId,
        String role,
        boolean isPrimary,
        AssignmentStatus status,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Instant createdAt,
        Instant updatedAt,
        String createdBy) {
}