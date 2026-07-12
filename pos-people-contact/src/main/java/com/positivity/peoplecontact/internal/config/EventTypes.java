package com.positivity.peoplecontact.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/**
 * Registry of all event types emitted by the pos-people-contact module, registered with
 * pos-event-receiver at startup with performance thresholds matching each operation's latency
 * characteristics.
 */
public final class EventTypes {

    private EventTypes() {
        // Utility class
    }

    /** All event type registrations for the people-contact module. */
    public static List<EventTypeRegistration> all() {
        return List.of(
                // PersonController
                EventTypeRegistration.write("PEOPLE_CONTACT_PERSON_CREATE", "Create a new person record")
                        .build(),
                EventTypeRegistration.write("PEOPLE_CONTACT_PERSON_UPDATE", "Update an existing person record")
                        .build(),
                EventTypeRegistration.write(
                                "PEOPLE_CONTACT_PERSON_RESOLVE",
                                "Resolve person by weighted match or create a new record")
                        .build(),
                EventTypeRegistration.write("PEOPLE_CONTACT_PERSON_DELETE", "Delete an existing person record")
                        .build(),

                // PersonBulkIngestController
                EventTypeRegistration.write("PEOPLE_CONTACT_BULK_INGEST", "Bulk import person records")
                        .build(),

                // UserPersonLinkController
                EventTypeRegistration.write("PEOPLE_CONTACT_USER_LINK_CREATE", "Link user to person")
                        .build(),
                EventTypeRegistration.fastRead("PEOPLE_CONTACT_USER_LINK_GET", "Get user links by person")
                        .build(),
                EventTypeRegistration.write("PEOPLE_CONTACT_USER_PERSON_LINK_DELETE", "Unlink user from person")
                        .build(),

                // PersonAccessController
                EventTypeRegistration.fastRead(
                                "PEOPLE_CONTACT_ACCESS_ROLES_LIST", "List available access roles for people")
                        .build(),
                EventTypeRegistration.fastRead(
                                "PEOPLE_CONTACT_ACCESS_ASSIGNMENTS_LIST", "List role assignments for a person")
                        .build(),
                EventTypeRegistration.write(
                                "PEOPLE_CONTACT_ACCESS_ASSIGNMENT_CREATE", "Create role assignment for a person")
                        .build(),
                EventTypeRegistration.write(
                                "PEOPLE_CONTACT_ACCESS_ASSIGNMENT_REVOKE", "Revoke role assignment for a person")
                        .build());
    }
}
