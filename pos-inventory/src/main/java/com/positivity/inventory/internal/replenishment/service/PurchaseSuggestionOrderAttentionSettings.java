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

    public PurchaseSuggestionOrderAttentionSettings(
            @Value("${pos.inventory.purchase-suggestion.order-placement-grace:PT1H}") @NonNull Duration grace) {
        if (grace.isNegative()) {
            throw new IllegalStateException(
                    "pos.inventory.purchase-suggestion.order-placement-grace must not be negative, was " + grace);
        }
        this.orderPlacementGrace = grace;
    }

    public @NonNull Duration orderPlacementGrace() {
        return orderPlacementGrace;
    }
}
