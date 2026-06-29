package com.positivity.inventory.internal.dto.cyclecount;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.enums.ApprovalTier;
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
 * Response DTO for cycle count adjustment operations.
 */
@Schema(description = "Result of a cycle count inventory adjustment, including approval state and posting outcome")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdjustmentResponse {

    @Schema(
            description = "Unique identifier of the adjustment record",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID adjustmentId;

    @Schema(
            description = "Identifier of the stock item the adjustment applies to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID stockItemId;

    @Schema(
            description = "Reason code explaining why the adjustment was made",
            example = "CYCLE_COUNT_VARIANCE",
            requiredMode = REQUIRED)
    @NotNull
    private String reasonCode;

    @Schema(
            description = "Net change in quantity on hand resulting from the adjustment (may be negative)",
            example = "-3",
            requiredMode = REQUIRED)
    @NotNull
    private Integer quantityChange;

    @Schema(
            description = "Unit cost captured at the moment the adjustment was created",
            example = "12.50",
            requiredMode = REQUIRED)
    @NotNull
    private BigDecimal costAtTimeOfAdjustment;

    @Schema(
            description = "Quantity on hand recorded before the count was applied",
            example = "15",
            requiredMode = REQUIRED)
    @NotNull
    private Integer quantityOnHandBefore;

    @Schema(description = "Quantity physically counted by the auditor", example = "12", requiredMode = REQUIRED)
    @NotNull
    private Integer countedQuantity;

    @Schema(
            description = "Current lifecycle status of the adjustment",
            example = "PENDING_APPROVAL",
            requiredMode = REQUIRED)
    @NotNull
    private AdjustmentStatus status;

    @Schema(
            description = "Approval tier required to authorize the adjustment, when manual approval applies",
            example = "TIER_1_MANAGER",
            requiredMode = NOT_REQUIRED)
    private ApprovalTier requiredApprovalTier;

    @Schema(
            description = "Identifier of the user who created the adjustment",
            example = "user-10042",
            requiredMode = REQUIRED)
    @NotNull
    private String createdByUserId;

    @Schema(
            description = "Identifier of the user who approved the adjustment, if approved",
            example = "user-20055",
            requiredMode = NOT_REQUIRED)
    private String approvedByUserId;

    @Schema(
            description = "Identifier of the user who rejected the adjustment, if rejected",
            example = "user-30077",
            requiredMode = NOT_REQUIRED)
    private String rejectedByUserId;

    @Schema(
            description = "Free-text reason supplied when the adjustment was rejected",
            example = "Recount required before posting",
            requiredMode = NOT_REQUIRED)
    private String rejectionReason;

    @Schema(
            description = "Timestamp when the adjustment was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the adjustment was last updated",
            example = "2026-01-15T10:05:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant updatedAt;

    @Schema(
            description = "Timestamp when the adjustment was approved, if approved",
            example = "2026-01-15T10:05:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant approvedAt;

    @Schema(
            description = "Timestamp when the adjustment was rejected, if rejected",
            example = "2026-01-15T10:05:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant rejectedAt;

    @Schema(
            description = "Timestamp when the adjustment was posted to the inventory ledger, if posted",
            example = "2026-01-15T10:10:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant postedAt;

    @Schema(
            description = "Identifier of the inventory ledger entry created when the adjustment was posted",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID ledgerEntryId;

    @Schema(
            description = "Error message describing why processing failed, if the adjustment failed",
            example = "Stock item not found",
            requiredMode = NOT_REQUIRED)
    private String errorMessage;

    @Schema(description = "Monetary value of the counted variance", example = "37.50", requiredMode = NOT_REQUIRED)
    private BigDecimal varianceValue;

    @Schema(
            description = "Variance expressed as a percentage of expected quantity on hand",
            example = "20.00",
            requiredMode = NOT_REQUIRED)
    private BigDecimal variancePercentage;
}
