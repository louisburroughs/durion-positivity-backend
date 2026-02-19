package com.positivity.people.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;

import java.time.LocalDate;
import java.util.UUID;

public record CreateStaffingAssignmentRequest(
        @NonNull @NotNull UUID personId,
        @NonNull @NotNull UUID locationId,
        @NonNull @NotBlank String role,
        boolean isPrimary,
        @NonNull @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo) {
}