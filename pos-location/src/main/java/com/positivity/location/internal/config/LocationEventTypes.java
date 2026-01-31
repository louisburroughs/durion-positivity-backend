package com.positivity.location.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types emitted by the pos-location module.
 * Each event type is pre-registered with the event service on application
 * startup.
 */
public final class LocationEventTypes {

    private LocationEventTypes() {
        // Utility class
    }

    // Location events
    public static final EventTypeRegistration LOCATION_LOCATION_CREATE = EventTypeRegistration
            .write("LOCATION_LOCATION_CREATE", "Create a new location").build();

    public static final EventTypeRegistration LOCATION_LOCATION_UPDATE = EventTypeRegistration
            .write("LOCATION_LOCATION_UPDATE", "Update an existing location").build();

    public static final EventTypeRegistration LOCATION_PARENT_ADD = EventTypeRegistration
            .write("LOCATION_PARENT_ADD", "Add a parent relationship to a location").build();

    // Bay events
    public static final EventTypeRegistration LOCATION_BAY_CREATE = EventTypeRegistration
            .write("LOCATION_BAY_CREATE", "Create a new bay for a location").build();

    public static final EventTypeRegistration LOCATION_BAY_MANAGE = EventTypeRegistration
            .write("LOCATION_BAY_MANAGE", "Create or update bays in bulk").build();

    // Mobile Unit events
    public static final EventTypeRegistration LOCATION_MOBILE_UNIT_CREATE = EventTypeRegistration
            .write("LOCATION_MOBILE_UNIT_CREATE", "Create a new mobile unit for a location").build();

    public static final EventTypeRegistration LOCATION_MOBILE_UNIT_MANAGE = EventTypeRegistration
            .write("LOCATION_MOBILE_UNIT_MANAGE", "Create or update mobile units in bulk").build();

    /**
     * Returns all event types for registration with the event service.
     *
     * @return list of all location event type registrations
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                // Location events
                LOCATION_LOCATION_CREATE,
                LOCATION_LOCATION_UPDATE,
                LOCATION_PARENT_ADD,
                // Bay events
                LOCATION_BAY_CREATE,
                LOCATION_BAY_MANAGE,
                // Mobile Unit events
                LOCATION_MOBILE_UNIT_CREATE,
                LOCATION_MOBILE_UNIT_MANAGE);
    }
}
