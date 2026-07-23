package com.positivity.inventory.internal.dto.cyclecount.plan;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Request to transition a cycle count plan to a new lifecycle status")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateCycleCountPlanStatusRequest {

    @Schema(description = "Target lifecycle status", example = "STARTED", requiredMode = REQUIRED)
    @NotNull
    private CycleCountPlanStatus status;
}
