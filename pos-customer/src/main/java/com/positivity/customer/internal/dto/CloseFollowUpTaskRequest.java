package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Closing a task always records what happened. A queue that can be emptied without an outcome
 * measures activity rather than result.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to complete or dismiss a follow-up task")
public class CloseFollowUpTaskRequest {

    @Schema(
            description = "What happened when the follow-up was worked",
            example = "Customer booked for 12 Aug",
            requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 500)
    private String outcome;

    @Schema(description = "Workorder created from the hand-off, when booking followed", requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID bookedWorkorderId;

    @Schema(description = "Appointment created from the hand-off, when booking followed", requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID bookedAppointmentId;
}
