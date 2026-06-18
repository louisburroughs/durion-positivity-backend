package com.positivity.inventory.internal.dto.cyclecount;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to submit a count for a cycle count task.
 */
@Schema(description = "Request to submit the initial physical count for a cycle count task")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubmitCountRequest {

    @Schema(
            description = "Identifier of the cycle count task being counted",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull(message = "Task ID is required")
    private UUID taskId;

    @Schema(
            description = "Identifier of the auditor submitting the count",
            example = "auditor-10042",
            requiredMode = REQUIRED)
    @NotNull(message = "Auditor ID is required")
    private String auditorId;

    @Schema(
            description = "Quantity physically counted by the auditor",
            example = "12",
            requiredMode = REQUIRED)
    @NotNull(message = "Actual quantity is required")
    @Min(value = 0, message = "Quantity must be zero or positive")
    private Integer actualQuantity;
}
