package com.positivity.inventory.internal.dto.cyclecount.schedule;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "A recurring cycle-count schedule and its current due state")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CycleCountScheduleResponse {

    @Schema(
            description = "Unique identifier of the schedule",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID scheduleId;

    @Schema(
            description = "Identifier of the location the schedule applies to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(
            description = "Optional single-zone filter within the location",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID zoneId;

    @Schema(description = "Optional SKU-category filter", example = "BRAKES", requiredMode = NOT_REQUIRED)
    private String skuCategory;

    @Schema(description = "Count frequency in days", example = "30", requiredMode = REQUIRED)
    @NotNull
    private Integer frequencyDays;

    @Schema(description = "Next date the schedule is due for counting", example = "2026-08-01", requiredMode = REQUIRED)
    @NotNull
    private LocalDate nextDueDate;

    @Schema(
            description = "Whether the scheduled runner auto-creates the next cycle-count plan when due",
            example = "true",
            requiredMode = REQUIRED)
    private boolean autoCreatePlan;

    @Schema(description = "Whether the schedule is active", example = "true", requiredMode = REQUIRED)
    private boolean active;

    @Schema(
            description = "Identifier of the user who created the schedule",
            example = "user-10042",
            requiredMode = REQUIRED)
    @NotNull
    private String createdBy;

    @Schema(
            description = "Timestamp when the schedule was created",
            example = "2026-07-01T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the schedule was last updated",
            example = "2026-07-15T10:05:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant updatedAt;
}
