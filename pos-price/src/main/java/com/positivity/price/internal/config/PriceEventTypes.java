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
         * Total: 9 event types.
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
                                                "Override price restrictions for a specific context or condition")
                                                .build(),

                                // PriceQuoteController - 1 event
                                EventTypeRegistration.search("PRICE_QUOTE_CALCULATE",
                                                "Calculate contextual quote pricing for product, location, and customer tier")
                                                .build(),

                                // PromotionOfferController - 3 events
                                EventTypeRegistration.write("PROMOTION_OFFER_CREATE", "Create a promotion offer")
                                                .apiVersion("1")
                                                .build(),
                                EventTypeRegistration.approval("PROMOTION_OFFER_ACTIVATE", "Activate a promotion offer")
                                                .apiVersion("1")
                                                .build(),
                                EventTypeRegistration.write("PROMOTION_OFFER_DEACTIVATE", "Deactivate a promotion offer")
                                                .apiVersion("1")
                                                .build(),

                                // PromotionEligibilityRuleController - 2 events
                                EventTypeRegistration.write("PROMOTION_RULE_CREATE", "Add an eligibility rule to a promotion")
                                                .apiVersion("1")
                                                .build(),
                                EventTypeRegistration.write("PROMOTION_RULE_DELETE", "Remove an eligibility rule from a promotion")
                                                .apiVersion("1")
                                                .build());
        }
}
