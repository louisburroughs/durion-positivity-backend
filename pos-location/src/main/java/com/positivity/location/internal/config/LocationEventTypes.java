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
    public static final EventTypeRegistration LOCATION_LOCATION_CREATE = EventTypeRegistration.write(
                    "LOCATION_LOCATION_CREATE", "Create a new location")
            .build();

    public static final EventTypeRegistration LOCATION_LOCATION_UPDATE = EventTypeRegistration.write(
                    "LOCATION_LOCATION_UPDATE", "Update an existing location")
            .build();

    public static final EventTypeRegistration LOCATION_LOCATION_DELETE = EventTypeRegistration.write(
                    "LOCATION_LOCATION_DELETE", "Delete an existing location")
            .build();

    public static final EventTypeRegistration LOCATION_PATCH =
            EventTypeRegistration.write("LOCATION_PATCH", "Patch a location").build();

    public static final EventTypeRegistration LOCATION_PARENT_ADD = EventTypeRegistration.write(
                    "LOCATION_PARENT_ADD", "Add a parent relationship to a location")
            .build();

    public static final EventTypeRegistration LOCATION_ROSTER_GET = EventTypeRegistration.fastRead(
                    "LOCATION_ROSTER_GET", "Get location roster for sync")
            .build();

    // Bay events
    public static final EventTypeRegistration LOCATION_BAY_CREATE = EventTypeRegistration.write(
                    "LOCATION_BAY_CREATE", "Create a new bay for a location")
            .build();

    public static final EventTypeRegistration LOCATION_BAY_UPDATE =
            EventTypeRegistration.write("LOCATION_BAY_UPDATE", "Update a bay").build();

    // Mobile Unit events
    public static final EventTypeRegistration LOCATION_MOBILE_UNIT_CREATE = EventTypeRegistration.write(
                    "LOCATION_MOBILE_UNIT_CREATE", "Create a new mobile unit for a location")
            .build();

    public static final EventTypeRegistration LOCATION_MOBILE_UNIT_UPDATE = EventTypeRegistration.write(
                    "LOCATION_MOBILE_UNIT_UPDATE", "Update an existing mobile unit")
            .build();

    public static final EventTypeRegistration LOCATION_MOBILE_UNIT_MANAGE = EventTypeRegistration.write(
                    "LOCATION_MOBILE_UNIT_MANAGE", "Create or update mobile units in bulk")
            .build();

    public static final EventTypeRegistration LOCATION_SERVICE_AREA_CREATE = EventTypeRegistration.write(
                    "LOCATION_SERVICE_AREA_CREATE", "Create a new service area")
            .build();

    public static final EventTypeRegistration LOCATION_SERVICE_AREA_PATCH = EventTypeRegistration.write(
                    "LOCATION_SERVICE_AREA_PATCH", "Patch an existing service area")
            .build();

    public static final EventTypeRegistration LOCATION_TRAVEL_BUFFER_POLICY_CREATE = EventTypeRegistration.write(
                    "LOCATION_TRAVEL_BUFFER_POLICY_CREATE", "Create a new travel buffer policy")
            .build();

    public static final EventTypeRegistration LOCATION_TRAVEL_BUFFER_POLICY_PATCH = EventTypeRegistration.write(
                    "LOCATION_TRAVEL_BUFFER_POLICY_PATCH", "Patch an existing travel buffer policy")
            .build();

    public static final EventTypeRegistration LOCATION_COVERAGE_RULES_REPLACE = EventTypeRegistration.write(
                    "LOCATION_COVERAGE_RULES_REPLACE", "Replace mobile unit coverage rules")
            .build();

    // Storage Location events
    public static final EventTypeRegistration LOCATION_STORAGE_LOCATION_LIST = EventTypeRegistration.fastRead(
                    "LOCATION_STORAGE_LOCATION_LIST", "List storage locations")
            .build();

    public static final EventTypeRegistration LOCATION_STORAGE_LOCATION_GET = EventTypeRegistration.fastRead(
                    "LOCATION_STORAGE_LOCATION_GET", "Get a storage location")
            .build();

    public static final EventTypeRegistration LOCATION_STORAGE_LOCATION_VALIDATE = EventTypeRegistration.fastRead(
                    "LOCATION_STORAGE_LOCATION_VALIDATE", "Validate a storage location")
            .build();

    public static final EventTypeRegistration LOCATION_STORAGE_LOCATION_CREATE = EventTypeRegistration.write(
                    "LOCATION_STORAGE_LOCATION_CREATE", "Create a storage location")
            .build();

    public static final EventTypeRegistration LOCATION_STORAGE_LOCATION_UPDATE = EventTypeRegistration.write(
                    "LOCATION_STORAGE_LOCATION_UPDATE", "Update or deactivate a storage location")
            .build();

    // Site Defaults events
    public static final EventTypeRegistration LOCATION_SITE_DEFAULTS_PUT = EventTypeRegistration.write(
                    "LOCATION_SITE_DEFAULTS_PUT", "Configure site default storage locations")
            .build();

    public static final EventTypeRegistration LOCATION_SITE_DEFAULTS_GET = EventTypeRegistration.fastRead(
                    "LOCATION_SITE_DEFAULTS_GET", "Get site default storage locations")
            .build();

    public static final EventTypeRegistration LOCATION_BULK_INGEST = EventTypeRegistration.write(
                    "LOCATION_BULK_INGEST", "Bulk ingest locations")
            .build();

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
                LOCATION_LOCATION_DELETE,
                LOCATION_PATCH,
                LOCATION_PARENT_ADD,
                LOCATION_ROSTER_GET,
                // Bay events
                LOCATION_BAY_CREATE,
                LOCATION_BAY_UPDATE,
                // Mobile Unit events
                LOCATION_MOBILE_UNIT_CREATE,
                LOCATION_MOBILE_UNIT_UPDATE,
                LOCATION_MOBILE_UNIT_MANAGE,
                LOCATION_SERVICE_AREA_CREATE,
                LOCATION_SERVICE_AREA_PATCH,
                LOCATION_TRAVEL_BUFFER_POLICY_CREATE,
                LOCATION_TRAVEL_BUFFER_POLICY_PATCH,
                LOCATION_COVERAGE_RULES_REPLACE,
                // Storage Location events
                LOCATION_STORAGE_LOCATION_LIST,
                LOCATION_STORAGE_LOCATION_GET,
                LOCATION_STORAGE_LOCATION_VALIDATE,
                LOCATION_STORAGE_LOCATION_CREATE,
                LOCATION_STORAGE_LOCATION_UPDATE,
                // Site Defaults events
                LOCATION_SITE_DEFAULTS_PUT,
                LOCATION_SITE_DEFAULTS_GET,
                LOCATION_BULK_INGEST);
    }
}
