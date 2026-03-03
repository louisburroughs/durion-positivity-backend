package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
    @Schema(description = "Adjustment event identifier", example = "550e8400-e29b-41d4-a716-446655440700")
    private UUID id;

    /**
     * ID of the original part being adjusted.
     */
    @Schema(description = "Original part identifier", example = "550e8400-e29b-41d4-a716-446655440500")
    private UUID originalPartId;

    /**
     * Description of the original part.
     */
    @Schema(description = "Original part description", example = "Brake Pad Set - Front")
    private String originalPartDescription;

    /**
     * Workorder ID.
     */
    @Schema(description = "Workorder identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID workorderId;

    /**
     * Type of adjustment: SUBSTITUTION, ADDITIONAL_RETURN, CORRECTION.
     */
    @Schema(description = "Adjustment type", example = "SUBSTITUTION")
    private String adjustmentType;

    /**
     * For SUBSTITUTION: ID of the substitute part.
     */
    @Schema(description = "Substitute part identifier for substitution adjustments", example = "550e8400-e29b-41d4-a716-446655440501")
    private UUID substitutedWithPartId;

    /**
     * For SUBSTITUTION: description of the substitute part.
     */
    @Schema(description = "Substitute part description", example = "Brake Pad Set - Ceramic")
    private String substitutedWithPartDescription;

    /**
     * Signed quantity adjustment (negative for returns, positive for additions).
     */
    @Schema(description = "Signed quantity adjustment", example = "-1")
    private BigDecimal quantityAdjustment;

    /**
     * Reason for adjustment.
     */
    @Schema(description = "Adjustment reason", example = "Equivalent part used due to stock shortage")
    private String reason;

    /**
     * User who performed the adjustment.
     */
    @Schema(description = "Actor identifier who performed adjustment", example = "tech@shop.local")
    private String performedBy;

    /**
     * When the adjustment occurred.
     */
    @Schema(description = "Adjustment timestamp")
    private Instant performedAt;

    /**
     * Optional additional notes.
     */
    @Schema(description = "Optional additional notes")
    private String notes;
}
