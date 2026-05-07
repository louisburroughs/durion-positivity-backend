package com.positivity.vehiclefitment.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/**
 * Registry of all event types emitted by the pos-vehicle-fitment module.
 * Each event type is registered with appropriate performance thresholds
 * based on expected operation latency characteristics.
 */
public final class EventTypes {

    private EventTypes() {
        // Utility class
    }

    /**
     * All event type registrations for the vehicle fitment module.
     * Total: 5 event types.
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                // VehicleApplicabilityHintController - 3 events
                EventTypeRegistration.write(
                                "VEHICLE_HINT_CREATED",
                                "Create a new vehicle applicability hint with fitment tags for a product")
                        .build(),
                EventTypeRegistration.write(
                                "VEHICLE_HINT_UPDATED",
                                "Update the fitment tags for an existing vehicle applicability hint")
                        .build(),
                EventTypeRegistration.write("VEHICLE_HINT_DELETED", "Delete an existing vehicle applicability hint")
                        .build(),
                EventTypeRegistration.search(
                                "FITMENT_PRODUCTS_FILTER",
                                "Filter products by vehicle attributes to find matching products")
                        .build(),
                EventTypeRegistration.write("VEHICLE_FITMENT_BULK_INGEST", "Bulk ingest vehicle fitment records")
                        .build());
    }
}
