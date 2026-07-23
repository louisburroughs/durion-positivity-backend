package com.positivity.inventory.internal.dto.cyclecount.schedule;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Summary of one due-schedule processing pass (odoo-parity I1, issue #1031). */
@Schema(description = "Summary of one cycle-count schedule processing pass")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CycleCountScheduleRunResultResponse {

    @Schema(description = "Active schedules whose next due date had arrived", example = "3", requiredMode = REQUIRED)
    private int schedulesDue;

    @Schema(description = "Cycle-count plans auto-created in this pass", example = "2", requiredMode = REQUIRED)
    private int plansCreated;

    @Schema(
            description = "Auto-create schedules skipped because a plan already existed for the due date",
            example = "0",
            requiredMode = REQUIRED)
    private int plansSkippedExisting;
}
