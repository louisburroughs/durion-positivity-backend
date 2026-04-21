package com.positivity.vehicle.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/**
 * Registry of all event types emitted by the pos-vehicle-inventory module.
 */
public final class EventTypes {

    private EventTypes() {
        // Utility class
    }

    public static List<EventTypeRegistration> all() {
        return List.of(
                EventTypeRegistration.write("VEHICLE_CREATE", "Create a vehicle record")
                        .build(),
                EventTypeRegistration.write("VEHICLE_UPDATE", "Update a vehicle record")
                        .build(),
                EventTypeRegistration.write("VEHICLE_DELETE", "Delete or deactivate a vehicle record")
                        .build(),
                EventTypeRegistration.search("VEHICLE_SEARCH", "Search vehicles")
                        .build(),
                EventTypeRegistration.write("VEHICLE_PREFERENCES_UPSERT", "Create or update vehicle preferences")
                        .build(),
                EventTypeRegistration.write("VEHICLE_PREFERENCES_MERGE", "Merge vehicle preferences")
                        .build(),
                EventTypeRegistration.write("VEHICLE_PREFERENCES_DELETE", "Delete vehicle preferences")
                        .build(),
                EventTypeRegistration.write("VEHICLE_BULK_INGEST", "Bulk ingest vehicle records")
                        .build());
    }
}
