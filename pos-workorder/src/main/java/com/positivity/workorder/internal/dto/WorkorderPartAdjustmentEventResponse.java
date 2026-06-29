package com.positivity.workorder.internal.dto;

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
 * Response DTO for part adjustment events.
 * Includes all event details plus part descriptions for display.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload for workorder part adjustment events")
public class WorkorderPartAdjustmentEventResponse {

    /**
     * Unique adjustment event ID.
     */
    @Schema(
            description = "Adjustment event identifier",
            example = "550e8400-e29b-41d4-a716-446655440700",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    /**
     * ID of the original part being adjusted.
     */
    @Schema(
            description = "Original part identifier",
            example = "550e8400-e29b-41d4-a716-446655440500",
            requiredMode = REQUIRED)
    @NotNull
    private UUID originalPartId;

    /**
     * Description of the original part.
     */
    @Schema(description = "Original part description", example = "Brake Pad Set - Front", requiredMode = NOT_REQUIRED)
    private String originalPartDescription;

    /**
     * Workorder ID.
     */
    @Schema(
            description = "Workorder identifier",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    /**
     * Type of adjustment: SUBSTITUTION, ADDITIONAL_RETURN, CORRECTION.
     */
    @Schema(description = "Adjustment type", example = "SUBSTITUTION", requiredMode = REQUIRED)
    @NotNull
    private String adjustmentType;

    /**
     * For SUBSTITUTION: ID of the substitute part.
     */
    @Schema(
            description = "Substitute part identifier for substitution adjustments",
            example = "550e8400-e29b-41d4-a716-446655440501",
            requiredMode = NOT_REQUIRED)
    private UUID substitutedWithPartId;

    /**
     * For SUBSTITUTION: description of the substitute part.
     */
    @Schema(
            description = "Substitute part description",
            example = "Brake Pad Set - Ceramic",
            requiredMode = NOT_REQUIRED)
    private String substitutedWithPartDescription;

    /**
     * Signed quantity adjustment (negative for returns, positive for additions).
     */
    @Schema(description = "Signed quantity adjustment", example = "-1", requiredMode = REQUIRED)
    @NotNull
    private BigDecimal quantityAdjustment;

    /**
     * Reason for adjustment.
     */
    @Schema(
            description = "Adjustment reason",
            example = "Equivalent part used due to stock shortage",
            requiredMode = NOT_REQUIRED)
    private String reason;

    /**
     * User who performed the adjustment.
     */
    @Schema(
            description = "Actor identifier who performed adjustment",
            example = "tech@shop.local",
            requiredMode = REQUIRED)
    @NotNull
    private String performedBy;

    /**
     * When the adjustment occurred.
     */
    @Schema(description = "Adjustment timestamp", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    @NotNull
    private Instant performedAt;

    /**
     * Optional additional notes.
     */
    @Schema(description = "Optional additional notes", example = "Customer notified", requiredMode = NOT_REQUIRED)
    private String notes;
}
