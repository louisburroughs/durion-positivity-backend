package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a price override applied to an order line item.
 * 
 * <p>
 * Tracks the complete lifecycle from request through approval to application.
 * Implements comprehensive audit trail requirements with immutable state
 * transitions.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "price_override")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceOverride {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID overrideId;

    /**
     * Order identifier this override applies to.
     */
    @Column(nullable = false, columnDefinition = "UUID")
    private UUID orderId;

    /**
     * Order line item identifier this override applies to.
     */
    @Column(nullable = false, columnDefinition = "UUID")
    private UUID orderLineId;

    /**
     * SKU or product identifier for the line item.
     */
    @Column(nullable = false, columnDefinition = "UUID")
    private UUID productId;

    /**
     * Original/baseline price from pricing service.
     */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal originalPrice;

    /**
     * Override price to be applied.
     */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal overridePrice;

    /**
     * Reason code for the override.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PriceOverrideReasonCode reasonCode;

    /**
     * Optional detailed justification for the override.
     */
    @Column(length = 2000)
    private String justification;

    /**
     * Current status of the override in its lifecycle.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OverrideStatus status;

    /**
     * User ID who requested the override (Service Advisor).
     */
    @Column(nullable = false, columnDefinition = "UUID")
    private UUID requestedByUserId;

    /**
     * User ID who approved the override (Manager).
     * Null if not yet approved or if auto-approved.
     */
    @Column(columnDefinition = "UUID")
    private UUID approvedByUserId;

    /**
     * User ID who rejected the override.
     * Null if not rejected.
     */
    @Column(columnDefinition = "UUID")
    private UUID rejectedByUserId;

    /**
     * Reason provided for rejection.
     */
    @Column(length = 1000)
    private String rejectionReason;

    /**
     * Timestamp when the override was created.
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp when the override was last updated.
     */
    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(nullable = false, updatable = false, length = 255)
    private String createdBy;

    @LastModifiedBy
    @Column(nullable = false, length = 255)
    private String updatedBy;

    /**
     * Timestamp when the override was approved.
     */
    private Instant approvedAt;

    /**
     * Timestamp when the override was rejected.
     */
    private Instant rejectedAt;

    /**
     * Timestamp when the override was applied to the order.
     */
    private Instant appliedAt;

    /**
     * Whether this override required manager approval.
     */
    @Column(nullable = false)
    private Boolean requiresApproval;

    /**
     * Whether this override affects commission calculation.
     * True for auto-approved overrides; false for pending-approval overrides.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean affectsCommission = false;

    @Column(unique = true, length = 128)
    private String idempotencyKey;

    /**
     * Calculates the absolute discount amount.
     * 
     * @return originalPrice - overridePrice
     */
    public BigDecimal getDiscountAmount() {
        return originalPrice.subtract(overridePrice);
    }

    /**
     * Calculates the discount percentage.
     * 
     * @return percentage discount (0-100)
     */
    public BigDecimal getDiscountPercentage() {
        if (originalPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return getDiscountAmount()
                .divide(originalPrice, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
