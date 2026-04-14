package com.positivity.inventory.internal.dto.cyclecount;

import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.enums.ApprovalTier;
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
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdjustmentResponse {

    private UUID adjustmentId;
    private UUID stockItemId;
    private String reasonCode;
    private Integer quantityChange;
    private BigDecimal costAtTimeOfAdjustment;
    private Integer quantityOnHandBefore;
    private Integer countedQuantity;
    private AdjustmentStatus status;
    private ApprovalTier requiredApprovalTier;
    private String createdByUserId;
    private String approvedByUserId;
    private String rejectedByUserId;
    private String rejectionReason;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant approvedAt;
    private Instant rejectedAt;
    private Instant postedAt;
    private UUID ledgerEntryId;
    private String errorMessage;
    private BigDecimal varianceValue;
    private BigDecimal variancePercentage;
}
