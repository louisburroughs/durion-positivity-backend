package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.customer.internal.enums.FollowUpType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to raise a follow-up task against a party")
public class CreateFollowUpTaskRequest {

    @Schema(description = "Why the follow-up is needed", example = "DECLINED_SERVICE_FOLLOWUP", requiredMode = REQUIRED)
    @NotNull
    private FollowUpType type;

    @Schema(description = "Vehicle the follow-up concerns, when vehicle-specific", requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID vehicleId;

    @Schema(description = "When the follow-up should be worked by", example = "2026-08-15", requiredMode = NOT_REQUIRED)
    @Nullable
    private LocalDate dueDate;

    @Schema(description = "CSR to own the task; unassigned tasks sit in the shared queue", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 255)
    private String assignedTo;

    @Schema(
            description = "Context for whoever works it",
            example = "Declined rear brake pads at 62k service",
            requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 500)
    private String reason;

    @Schema(description = "Workorder the follow-up originated from", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 64)
    private String sourceWorkorderId;

    @Schema(description = "Free-text notes", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 2000)
    private String notes;
}
