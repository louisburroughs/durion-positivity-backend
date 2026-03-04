package com.positivity.inventory.internal.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.shared.id.UUIDv7Id;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents an inventory adjustment proposed from a cycle count.
 * 
 * <p>
 * Tracks the complete lifecycle from proposal through approval/rejection to
 * posting.
 * Implements audit trail requirements with immutable state transitions.
 */
@Entity
@Table(name = "cycle_count_adjustment")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class CycleCountAdjustment {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID adjustmentId;

    /**
     * SKU identifier for the stock item being adjusted.
     */
    @Column(nullable = false)
    private UUID stockItemId;

    /**
     * Reason code for the adjustment (e.g., 'CYCLE_COUNT_SHRINK',
     * 'CYCLE_COUNT_OVERAGE').
     */
    @Column(nullable = false)
    private String reasonCode;

    /**
     * The variance quantity to be applied. Can be positive (overage) or negative
     * (shrinkage).
     */
    @Column(nullable = false)
    private Integer quantityChange;

    /**
     * The item's cost price at the time the adjustment was proposed.
     * Used for threshold evaluation based on monetary value.
     */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal costAtTimeOfAdjustment;

    /**
     * Quantity on-hand in system before the adjustment.
     */
    @Column(nullable = false)
    private Integer quantityOnHandBefore;

    /**
     * Actual counted quantity that triggered this adjustment.
     */
    @Column(nullable = false)
    private Integer countedQuantity;

    /**
     * Current status of the adjustment in its lifecycle.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdjustmentStatus status;

    /**
     * Required approval tier if approval is needed.
     * Null if auto-approved.
     */
    @Enumerated(EnumType.STRING)
    private ApprovalTier requiredApprovalTier;

    /**
     * User ID who initiated the cycle count/adjustment.
     */
    @Column(nullable = false)
    private String createdByUserId;

    /**
     * User ID who approved the adjustment.
     * Null if not yet approved or if rejected.
     */
    private String approvedByUserId;

    /**
     * User ID who rejected the adjustment.
     * Null if not rejected.
     */
    private String rejectedByUserId;

    /**
     * Reason provided for rejection.
     * Required when status is REJECTED.
     */
    @Column(length = 1000)
    private String rejectionReason;

    /**
     * Timestamp when the adjustment was created.
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp when the adjustment was last updated.
     */
    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Timestamp when the adjustment was approved.
     */
    private Instant approvedAt;

    /**
     * Timestamp when the adjustment was rejected.
     */
    private Instant rejectedAt;

    /**
     * Timestamp when the adjustment was posted to the ledger.
     */
    private Instant postedAt;

    /**
     * Reference to the inventory ledger entry created when posted.
     * Null until adjustment is posted.
     */
    private UUID ledgerEntryId;

    /**
     * Error message if status is FAILED.
     */
    @Column(length = 2000)
    private String errorMessage;

    /**
     * Calculates the absolute monetary value of the variance.
     * 
     * @return absolute value of (quantityChange * costAtTimeOfAdjustment)
     */
    public BigDecimal getVarianceValue() {
        return costAtTimeOfAdjustment.multiply(BigDecimal.valueOf(Math.abs(quantityChange)));
    }

    /**
     * Calculates the percentage variance relative to quantity on-hand.
     * 
     * @return percentage variance, or 100% if quantityOnHandBefore is 0
     */
    public BigDecimal getVariancePercentage() {
        if (quantityOnHandBefore == 0) {
            return BigDecimal.valueOf(100);
        }
        return BigDecimal.valueOf(Math.abs(quantityChange))
                .divide(BigDecimal.valueOf(Math.abs(quantityOnHandBefore)), 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
