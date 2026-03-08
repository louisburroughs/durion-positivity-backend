package com.positivity.order.service.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Result from applying a price override.
 * status = APPROVED means applied immediately; PENDING_APPROVAL means queued
 * for review.
 */
public record PriceOverrideResult(
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
        Instant createdAt,
        String message) {
}