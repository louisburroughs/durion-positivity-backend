package com.positivity.shopmanager.service.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.NonNull;

/** Request payload for bypassing a scheduling conflict with manager permission. */
@Value
@Builder
@Schema(description = "Request to bypass a scheduling conflict with manager permission")
public class ConflictOverrideRequest {
    @Schema(
            description = "Appointment identifier whose conflict is being overridden",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NonNull
    @NotNull
    UUID appointmentId;

    /** Non-blank reason for the override, e.g. "Customer waiting on-site". */
    @Schema(
            description = "Non-blank reason justifying the override",
            example = "Customer waiting on-site",
            requiredMode = REQUIRED)
    @NonNull
    @NotBlank
    String overrideReason;

    /** JSON string describing the conflict, e.g. {"type":"TECHNICIAN_DOUBLE_BOOKED","resourceId":"..."}. */
    @Schema(
            description = "Optional JSON string describing the conflict being overridden",
            example = "{\"type\":\"TECHNICIAN_DOUBLE_BOOKED\",\"resourceId\":\"01960003-0000-7000-8000-000000000010\"}",
            requiredMode = NOT_REQUIRED)
    String conflictDetails;
}
