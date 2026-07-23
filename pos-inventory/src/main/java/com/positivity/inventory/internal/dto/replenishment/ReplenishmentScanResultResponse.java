package com.positivity.inventory.internal.dto.replenishment;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Summary of one batch replenishment scan run (CAP-217 / odoo-parity F1).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Summary of a batch replenishment scan run over all replenishment policies")
public class ReplenishmentScanResultResponse {

    @Schema(
            description = "Number of replenishment policies evaluated by the scan",
            example = "42",
            requiredMode = REQUIRED)
    private int policiesEvaluated;

    @Schema(
            description = "Number of policies whose on-hand quantity was below the configured minimum",
            example = "5",
            requiredMode = REQUIRED)
    private int policiesBelowMinimum;

    @Schema(
            description = "Number of new replenishment tasks created by the scan",
            example = "3",
            requiredMode = REQUIRED)
    private int tasksCreated;

    @Schema(
            description = "Number of already-open replenishment tasks whose quantity was refreshed to the current need",
            example = "2",
            requiredMode = REQUIRED)
    private int tasksRefreshed;

    @Schema(
            description = "Number of policies skipped without evaluation because they are inactive or snoozed",
            example = "1",
            requiredMode = REQUIRED)
    private int policiesSkipped;

    @Schema(description = "Timestamp at which the scan ran", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    @NotNull
    private String scanAt;
}
