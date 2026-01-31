package com.positivity.price.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types emitted by the pos-price module.
 * Each event type is registered with appropriate performance thresholds
 * based on expected operation latency characteristics.
 */
public final class PriceEventTypes {

    private PriceEventTypes() {
        // Utility class
    }

    /**
     * All event type registrations for the price module.
     * Total: 3 event types.
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                // PriceNormalizationController - 1 event
                EventTypeRegistration.write("PRICE_NORMALIZATION_NORMALIZE",
                        "Normalize and standardize pricing data across the system").build(),

                // PriceRestrictionsController - 2 events
                EventTypeRegistration.search("PRICE_RESTRICTIONS_EVALUATE",
                        "Evaluate whether a price is within allowed restrictions").build(),
                EventTypeRegistration.write("PRICE_RESTRICTIONS_OVERRIDE",
                        "Override price restrictions for a specific context or condition").build());
    }
}
