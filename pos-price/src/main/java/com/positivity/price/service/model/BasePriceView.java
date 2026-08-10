package com.positivity.price.service.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read model for one base-price effective window (half-open: {@code effectiveFrom <= t < effectiveTo};
 * null {@code effectiveTo} = open-ended).
 *
 * Issue: #1233
 */
public record BasePriceView(
        UUID id,
        UUID productId,
        BigDecimal msrp,
        String currency,
        Instant effectiveFrom,
        @Nullable Instant effectiveTo) {}
