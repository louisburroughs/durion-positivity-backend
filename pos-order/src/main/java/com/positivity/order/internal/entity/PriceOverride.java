package com.positivity.order.internal.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a price override applied to an order line item.
 * 
 * <p>
 * Tracks the complete lifecycle from request through approval to application.
 * Implements comprehensive audit trail requirements with immutable state
 * transitions.
 */
@Entity
@Table(name = "price_override")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long overrideId;

    /**
     * Order identifier this override applies to.
     */
    @Column(nullable = false)
    private String orderId;

    /**
     * Order line item identifier this override applies to.
     */
    @Column(nullable = false)
    private String orderLineId;

    /**
     * SKU or product identifier for the line item.
     */
    @Column(nullable = false)
    private String productId;

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
    @Column(nullable = false)
    private String requestedByUserId;

    /**
     * User ID who approved the override (Manager).
     * Null if not yet approved or if auto-approved.
     */
    private String approvedByUserId;

    /**
     * User ID who rejected the override.
     * Null if not rejected.
     */
    private String rejectedByUserId;

    /**
     * Reason provided for rejection.
     */
    @Column(length = 1000)
    private String rejectionReason;

    /**
     * Timestamp when the override was created.
     */
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp when the override was last updated.
     */
    @Column(nullable = false)
    private Instant updatedAt;

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

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

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
