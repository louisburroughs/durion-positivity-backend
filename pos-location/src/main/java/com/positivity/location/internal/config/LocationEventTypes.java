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

        public static final EventTypeRegistration LOCATION_CREATED = EventTypeRegistration
                        .write("LOCATION_CREATED", "Create a location").build();

        public static final EventTypeRegistration LOCATION_UPDATED = EventTypeRegistration
                        .write("LOCATION_UPDATED", "Update a location").build();

        public static final EventTypeRegistration LOCATION_DELETED = EventTypeRegistration
                        .write("LOCATION_DELETED", "Delete a location").build();

        public static final EventTypeRegistration LOCATION_GET = EventTypeRegistration
                        .fastRead("LOCATION_GET", "Get location by ID").build();

        public static final EventTypeRegistration LOCATION_LIST = EventTypeRegistration
                        .fastRead("LOCATION_LIST", "List locations").build();

        /**
         * Returns all event types for registration with the event service.
         *
         * @return list of all location event type registrations
         */
        public static List<EventTypeRegistration> all() {
                return List.of(
                                LOCATION_CREATED,
                                LOCATION_UPDATED,
                                LOCATION_DELETED,
                                LOCATION_GET,
                                LOCATION_LIST);
        }
}
