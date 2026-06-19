package com.positivity.inventory.internal.dto.cyclecount.plan;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Request to create a cycle count plan scheduling counts across a location and zones")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateCycleCountPlanRequest {

    @Schema(
            description = "Identifier of the location the plan applies to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(
            description = "Identifiers of the zones within the location to include in the plan",
            example = "[\"01960003-0000-7000-8000-000000000002\"]",
            requiredMode = NOT_REQUIRED)
    private List<UUID> zoneIds;

    @Schema(
            description = "Human-readable name for the cycle count plan",
            example = "Q1 perishables count",
            requiredMode = REQUIRED)
    @NotBlank
    private String planName;

    @Schema(
            description = "Date on which the cycle count is scheduled to occur",
            example = "2026-01-15",
            requiredMode = REQUIRED)
    @NotNull
    private LocalDate scheduledDate;
}
