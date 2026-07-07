package com.positivity.people.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "Request to create a staffing assignment")
public class CreateStaffingAssignmentRequest {

    @NonNull
    @NotNull
    @Schema(
            description = "Person identifier",
            example = "123e4567-e89b-12d3-a456-426614174000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @NonNull
    @NotNull
    @Schema(
            description = "Location identifier",
            example = "22222222-2222-2222-2222-222222222222",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID locationId;

    @Nullable
    @NotBlank
    @Schema(description = "Assignment role", example = "TECHNICIAN", requiredMode = Schema.RequiredMode.REQUIRED)
    private String role;

    @JsonProperty("isPrimary")
    @Schema(
            description = "Whether this assignment is primary",
            example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean primary;

    @NonNull
    @NotNull
    @Schema(
            description = "Assignment effective start date",
            example = "2026-02-16",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate effectiveFrom;

    @Schema(
            description = "Assignment effective end date",
            example = "2026-12-31",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private LocalDate effectiveTo;

    public CreateStaffingAssignmentRequest() {}

    public CreateStaffingAssignmentRequest(
            UUID personId,
            UUID locationId,
            String role,
            boolean primary,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        this.personId = personId;
        this.locationId = locationId;
        this.role = role;
        this.primary = primary;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
    }

    public UUID getPersonId() {
        return personId;
    }

    public void setPersonId(UUID personId) {
        this.personId = personId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    @JsonProperty("isPrimary")
    public boolean isPrimary() {
        return primary;
    }

    @JsonProperty("isPrimary")
    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    @AssertTrue(message = "effectiveTo must be on or after effectiveFrom")
    public boolean isEffectiveDateRangeValid() {
        if (effectiveFrom == null || effectiveTo == null) {
            return true;
        }
        return !effectiveTo.isBefore(effectiveFrom);
    }
}
