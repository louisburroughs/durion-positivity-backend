package com.positivity.shopmanager.service.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.NonNull;

/**
 * Request payload for assigning mechanics and an optional resource to an
 * appointment.
 */
@Value
@Builder
@Schema(description = "Request to assign mechanics and an optional resource to an appointment")
public class CreateAssignmentRequest {
    @Schema(
            description = "Appointment identifier the mechanics are assigned to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NonNull
    @NotNull
    UUID appointmentId;

    /** At least one entry with role LEAD is required (AC-4). */
    @Schema(
            description = "Mechanics to assign; at least one entry with role LEAD is required",
            requiredMode = REQUIRED)
    @NonNull
    @NotNull
    @NotEmpty
    @Valid
    List<MechanicAssignmentItem> mechanics;

    /** Optional — bay or mobile unit to associate with this assignment. */
    @Schema(
            description = "Optional resource (bay or mobile unit) to associate with the assignment",
            example = "01960003-0000-7000-8000-000000000005",
            requiredMode = NOT_REQUIRED)
    UUID resourceId;

    @Schema(
            description = "Type of the associated resource (e.g. BAY or MOBILE_UNIT)",
            example = "BAY",
            requiredMode = NOT_REQUIRED)
    String resourceType;

    /** When true the caller is asserting override for detected conflicts (AC-5). */
    @Schema(
            description = "When true, the caller asserts override authority for detected conflicts",
            example = "false",
            requiredMode = NOT_REQUIRED)
    boolean override;

    @Schema(
            description = "Reason for overriding detected conflicts; required when override is true",
            example = "Customer waiting on-site",
            requiredMode = NOT_REQUIRED)
    String overrideReason;
}
