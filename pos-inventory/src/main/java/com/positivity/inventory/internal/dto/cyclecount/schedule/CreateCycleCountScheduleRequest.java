package com.positivity.inventory.internal.dto.cyclecount.schedule;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Request to create a recurring cycle-count schedule for a location")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateCycleCountScheduleRequest {

    @Schema(
            description = "Identifier of the location the schedule applies to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(
            description = "Optional single-zone filter within the location",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID zoneId;

    @Schema(
            description = "Optional SKU-category filter (plain string, metadata-only in v1)",
            example = "BRAKES",
            requiredMode = NOT_REQUIRED)
    @Size(max = 100)
    private String skuCategory;

    @Schema(description = "Count frequency in days", example = "30", requiredMode = REQUIRED)
    @NotNull
    @Positive
    private Integer frequencyDays;

    @Schema(
            description = "First date the schedule is due for counting",
            example = "2026-08-01",
            requiredMode = REQUIRED)
    @NotNull
    private LocalDate nextDueDate;

    @Schema(
            description = "Whether the scheduled runner auto-creates the next cycle-count plan when due"
                    + " (false = the schedule only appears in the due-for-count view); defaults to false",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private Boolean autoCreatePlan;
}
