package com.positivity.people.internal.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;

import java.time.LocalDate;
import java.util.UUID;

public record UpdateStaffingAssignmentRequest(
        @NonNull @NotNull UUID personId,
        @NonNull @NotNull UUID locationId,
        @NonNull @NotBlank String role,
        boolean isPrimary,
        @NonNull @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo) {

    @AssertTrue(message = "effectiveTo must be on or after effectiveFrom")
    public boolean isEffectiveDateRangeValid() {
        if (effectiveFrom == null || effectiveTo == null) {
            return true;
        }
        return !effectiveTo.isBefore(effectiveFrom);
    }
}
