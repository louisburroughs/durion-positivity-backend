package com.positivity.workorder.internal.dto;

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
public class WorkorderPartAdjustmentEventResponse {

    /**
     * Unique adjustment event ID.
     */
    private UUID id;

    /**
     * ID of the original part being adjusted.
     */
    private UUID originalPartId;

    /**
     * Description of the original part.
     */
    private String originalPartDescription;

    /**
     * Workorder ID.
     */
    private UUID workorderId;

    /**
     * Type of adjustment: SUBSTITUTION, ADDITIONAL_RETURN, CORRECTION.
     */
    private String adjustmentType;

    /**
     * For SUBSTITUTION: ID of the substitute part.
     */
    private UUID substitutedWithPartId;

    /**
     * For SUBSTITUTION: description of the substitute part.
     */
    private String substitutedWithPartDescription;

    /**
     * Signed quantity adjustment (negative for returns, positive for additions).
     */
    private BigDecimal quantityAdjustment;

    /**
     * Reason for adjustment.
     */
    private String reason;

    /**
     * User who performed the adjustment.
     */
    private UUID performedBy;

    /**
     * When the adjustment occurred.
     */
    private Instant performedAt;

    /**
     * Optional additional notes.
     */
    private String notes;
}
