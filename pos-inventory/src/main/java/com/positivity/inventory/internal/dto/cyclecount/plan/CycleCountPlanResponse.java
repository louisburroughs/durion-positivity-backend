package com.positivity.inventory.internal.dto.cyclecount.plan;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "A cycle count plan describing scheduled counts for a location and its current state")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CycleCountPlanResponse {

    @Schema(
            description = "Unique identifier of the cycle count plan",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID planId;

    @Schema(
            description = "Identifier of the location the plan applies to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(
            description = "Identifiers of the zones within the location covered by the plan",
            example = "[\"01960003-0000-7000-8000-000000000003\"]",
            requiredMode = NOT_REQUIRED)
    private List<UUID> zoneIds;

    @Schema(
            description = "Human-readable name of the cycle count plan",
            example = "Q1 perishables count",
            requiredMode = REQUIRED)
    @NotNull
    private String planName;

    @Schema(
            description = "Date on which the cycle count is scheduled to occur",
            example = "2026-01-15",
            requiredMode = REQUIRED)
    @NotNull
    private LocalDate scheduledDate;

    @Schema(
            description = "Current lifecycle status of the plan",
            example = "SCHEDULED",
            requiredMode = REQUIRED)
    @NotNull
    private String status;

    @Schema(
            description = "Identifier of the user who created the plan",
            example = "user-10042",
            requiredMode = REQUIRED)
    @NotNull
    private String createdBy;

    @Schema(
            description = "Timestamp when the plan was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the plan was last updated",
            example = "2026-01-15T10:05:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant updatedAt;
}
