package com.positivity.inventory.internal.replenishment.service;

import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * CAP:550 S36 (#2525, PROPOSED 9): how long after conversion pos-order's order may be missing from the copy before a
 * CONVERTED suggestion reads {@code ORDER_NOT_PLACED}. {@code pos.inventory.purchase-suggestion.order-placement-grace},
 * default one hour.
 */
@Component
public class PurchaseSuggestionOrderAttentionSettings {

    private final Duration orderPlacementGrace;
    private final Duration attentionLookback;

    public PurchaseSuggestionOrderAttentionSettings(
            @Value("${pos.inventory.purchase-suggestion.order-placement-grace:PT1H}") @NonNull Duration grace,
            @Value("${pos.inventory.purchase-suggestion.attention-lookback:P90D}") @NonNull Duration lookback) {
        if (grace.isNegative()) {
            throw new IllegalStateException(
                    "pos.inventory.purchase-suggestion.order-placement-grace must not be negative, was " + grace);
        }
        if (lookback.isNegative() || lookback.isZero()) {
            throw new IllegalStateException(
                    "pos.inventory.purchase-suggestion.attention-lookback must be positive, was " + lookback);
        }
        this.orderPlacementGrace = grace;
        this.attentionLookback = lookback;
    }

    public @NonNull Duration orderPlacementGrace() {
        return orderPlacementGrace;
    }

    /** How far back the post-scan gauge looks at CONVERTED suggestions (reads assess any row on demand). */
    public @NonNull Duration attentionLookback() {
        return attentionLookback;
    }
}
