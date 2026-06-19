package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import lombok.Data;

/**
 * Request payload for rescheduling an appointment.
 *
 * <p>
 * CAP-249 Story #11: reason is mandatory (enum); rescheduleReasonNotes is
 * required when {@code reason == OTHER} or when overriding a hard conflict.
 */
@Data
@Schema(description = "Request to reschedule an appointment to a new time window with a mandatory reason")
public class RescheduleAppointmentRequest {

    @Schema(
            description = "New appointment start instant in UTC (ISO-8601)",
            example = "2026-06-19T08:00:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant newStartAt;

    @Schema(
            description = "New appointment end instant in UTC (ISO-8601); must be after newStartAt",
            example = "2026-06-19T10:00:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant newEndAt;

    /** Mandatory reschedule reason. */
    @Schema(description = "Mandatory reschedule reason code", example = "CUSTOMER_REQUEST", requiredMode = REQUIRED)
    @NotNull
    private RescheduleReasonCode reason;

    /**
     * Optional free-text notes; required when reason is OTHER or when overriding
     * a hard scheduling conflict.
     */
    @Schema(
            description = "Optional notes; required when reason is OTHER or when overriding a hard conflict",
            example = "Mechanic out sick; moved to next available slot",
            requiredMode = NOT_REQUIRED)
    @Size(max = 1000)
    private String rescheduleReasonNotes;

    /** Whether to notify the customer of this reschedule (defaults to true). */
    @Schema(
            description = "Whether to notify the customer of this reschedule (defaults to true)",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private boolean notifyCustomer = true;
}
