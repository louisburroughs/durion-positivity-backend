package com.positivity.order.service.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Detailed view of a price override, safe to cross the service boundary.
 */
public record PriceOverrideDetail(
        UUID overrideId,
        String orderId,
        String orderLineId,
        String productId,
        BigDecimal originalPrice,
        BigDecimal overridePrice,
        BigDecimal discountAmount,
        BigDecimal discountPercentage,
        String reasonCode,
        String justification,
        String status,
        Boolean requiresApproval,
        Boolean affectsCommission,
        String requestedByUserId,
        String approvedByUserId,
        String rejectedByUserId,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt,
        Instant approvedAt,
        Instant rejectedAt,
        Instant appliedAt) {}
