package com.positivity.inventory.internal.dto.cyclecount.plan;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Request to generate count tasks for a cycle count plan")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerateCycleCountTasksRequest {

    @Schema(
            description = "Identifier of the auditor the generated tasks are assigned to",
            example = "auditor-10042",
            requiredMode = REQUIRED)
    @NotBlank
    // Matches cycle_count_task.auditor_id varchar(100): reject over-long values as a 400 up
    // front instead of a constraint violation after the whole generation pass has run.
    @Size(max = 100)
    private String auditorId;
}
