package com.positivity.inventory.internal.dto.cyclecount.tolerance;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for a cycle-count tolerance configuration.
 */
@Schema(description = "A tolerance configuration for cycle-count reconciliation")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CycleCountToleranceResponse {

    @Schema(
            description = "Unique identifier of the tolerance configuration",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID toleranceId;

    @Schema(
            description = "Catalog product this tolerance scopes to; null for a product-agnostic row",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID productId;

    @Schema(
            description = "Storage location this tolerance scopes to; null for a location-agnostic row",
            example = "TANK-04",
            requiredMode = NOT_REQUIRED)
    private String storageLocation;

    @Schema(
            description = "Maximum acceptable absolute variance, in the product's base UoM",
            example = "50",
            requiredMode = NOT_REQUIRED)
    private BigDecimal absoluteTolerance;

    @Schema(
            description = "Maximum acceptable variance as a percentage of book quantity",
            example = "1.5",
            requiredMode = NOT_REQUIRED)
    private BigDecimal percentageTolerance;

    @Schema(description = "Whether this configuration is in effect", example = "true", requiredMode = REQUIRED)
    private boolean active;

    @Schema(
            description = "Identifier of the user who created this configuration",
            example = "user-10042",
            requiredMode = REQUIRED)
    @NotNull
    private String createdBy;

    @Schema(
            description = "Timestamp when this configuration was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(
            description = "Timestamp when this configuration was last updated",
            example = "2026-01-15T10:05:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant updatedAt;
}
