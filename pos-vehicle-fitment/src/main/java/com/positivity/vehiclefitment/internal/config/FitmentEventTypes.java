package com.positivity.vehiclefitment.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types emitted by the pos-vehicle-fitment module.
 * Each event type is registered with appropriate performance thresholds
 * based on expected operation latency characteristics.
 */
public final class FitmentEventTypes {

    private FitmentEventTypes() {
        // Utility class
    }

    /**
     * All event type registrations for the vehicle fitment module.
     * Total: 4 event types.
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                // VehicleApplicabilityHintController - 3 events
                EventTypeRegistration.write("FITMENT_HINT_CREATE",
                        "Create a new vehicle applicability hint with fitment tags for a product").build(),
                EventTypeRegistration.write("FITMENT_HINT_UPDATE",
                        "Update the fitment tags for an existing vehicle applicability hint").build(),
                EventTypeRegistration.write("FITMENT_HINT_DELETE",
                        "Delete an existing vehicle applicability hint").build(),
                EventTypeRegistration.search("FITMENT_PRODUCTS_FILTER",
                        "Filter products by vehicle attributes to find matching products").build());
    }
}
