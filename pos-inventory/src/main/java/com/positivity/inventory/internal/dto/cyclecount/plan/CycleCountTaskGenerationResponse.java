package com.positivity.inventory.internal.dto.cyclecount.plan;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.dto.cyclecount.CycleCountTaskResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Outcome of one plan-driven task generation pass. */
@Schema(description = "Summary of one cycle-count task generation pass for a plan")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CycleCountTaskGenerationResponse {

    @Schema(
            description = "Plan the tasks were generated for",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID planId;

    @Schema(description = "Plan lifecycle status after generation", example = "STARTED", requiredMode = REQUIRED)
    private String planStatus;

    @Schema(description = "Storage locations scanned for book stock", example = "10", requiredMode = REQUIRED)
    private int locationsScanned;

    @Schema(description = "Count tasks created in this pass", example = "12", requiredMode = REQUIRED)
    private int tasksCreated;

    @Schema(
            description = "(bin, SKU) pairs skipped because the plan already has a task for them",
            example = "0",
            requiredMode = REQUIRED)
    private int tasksSkippedExisting;

    @Schema(description = "The tasks created in this pass", requiredMode = REQUIRED)
    private List<CycleCountTaskResponse> tasks;
}
