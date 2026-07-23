package com.positivity.inventory.internal.dto.cyclecount.schedule;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partial update for a cycle-count schedule: null fields are left unchanged
 * (v1 offers no way to clear the zone/SKU-category filters — recreate the
 * schedule instead).
 */
@Schema(description = "Partial update of a recurring cycle-count schedule; null fields are left unchanged")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateCycleCountScheduleRequest {

    @Schema(
            description = "New single-zone filter within the location",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID zoneId;

    @Schema(description = "New SKU-category filter", example = "BRAKES", requiredMode = NOT_REQUIRED)
    @Size(max = 100)
    private String skuCategory;

    @Schema(description = "New count frequency in days", example = "14", requiredMode = NOT_REQUIRED)
    @Positive
    private Integer frequencyDays;

    @Schema(description = "New next due date", example = "2026-09-01", requiredMode = NOT_REQUIRED)
    private LocalDate nextDueDate;

    @Schema(
            description = "Whether the scheduled runner auto-creates the next cycle-count plan when due",
            example = "false",
            requiredMode = NOT_REQUIRED)
    private Boolean autoCreatePlan;

    @Schema(description = "Whether the schedule is active", example = "true", requiredMode = NOT_REQUIRED)
    private Boolean active;
}
