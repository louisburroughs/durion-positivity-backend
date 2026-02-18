package com.positivity.people.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types emitted by the pos-people module.
 * Each event type is registered with appropriate performance thresholds
 * based on expected operation latency characteristics.
 */
public final class PeopleEventTypes {

        private PeopleEventTypes() {
                // Utility class
        }

        /**
         * All event type registrations for the people module.
         * Total: 21 event types.
         */
        public static List<EventTypeRegistration> all() {
                return List.of(
                                // PersonController - 2 events
                                EventTypeRegistration.write("PEOPLE_PERSON_CREATE",
                                                "Create a new person record").build(),
                                EventTypeRegistration.write("PEOPLE_PERSON_UPDATE",
                                                "Update an existing person record").build(),

                                // LocationController - 5 events
                                EventTypeRegistration.write("PEOPLE_LOCATION_CREATED",
                                                "Create a new location").build(),
                                EventTypeRegistration.write("PEOPLE_LOCATION_UPDATED",
                                                "Update an existing location").build(),
                                EventTypeRegistration.write("PEOPLE_LOCATION_DELETED",
                                                "Soft-delete an existing location").build(),
                                EventTypeRegistration.write("PEOPLE_STAFF_ASSIGNED",
                                                "Assign a person to a location").build(),
                                EventTypeRegistration.write("PEOPLE_STAFF_UNASSIGNED",
                                                "Unassign a person from a location").build(),

                                // WorkSessionController - 4 events
                                EventTypeRegistration.write("PEOPLE_WORK_SESSION_START",
                                                "Start a work session for a person").build(),
                                EventTypeRegistration.write("PEOPLE_WORK_SESSION_STOP",
                                                "Stop an active work session").build(),
                                EventTypeRegistration.write("PEOPLE_WORK_SESSION_BREAK_START",
                                                "Start a break within an active work session").build(),
                                EventTypeRegistration.write("PEOPLE_WORK_SESSION_BREAK_STOP",
                                                "End a break within a work session").build(),

                                // TimeEntryAdjustmentController - 2 events
                                EventTypeRegistration.write("PEOPLE_TIME_ENTRY_ADJUSTMENT_CREATE",
                                                "Create a time entry adjustment request").build(),
                                EventTypeRegistration.approval("PEOPLE_TIME_ENTRY_ADJUSTMENT_APPROVE",
                                                "Approve a pending time entry adjustment").build(),

                                // TimeEntryApprovalController - 2 events
                                EventTypeRegistration.approval("PEOPLE_TIME_ENTRY_APPROVE",
                                                "Batch approve time entries").build(),
                                EventTypeRegistration.approval("PEOPLE_TIME_ENTRY_REJECT",
                                                "Batch reject time entries").build(),

                                // TimeEntryExceptionController - 4 events
                                EventTypeRegistration.write("PEOPLE_TIME_ENTRY_EXCEPTION_CREATE",
                                                "Create a time entry exception record").build(),
                                EventTypeRegistration.write("PEOPLE_TIME_ENTRY_EXCEPTION_ACKNOWLEDGE",
                                                "Acknowledge an exception").build(),
                                EventTypeRegistration.write("PEOPLE_TIME_ENTRY_EXCEPTION_RESOLVE",
                                                "Mark an exception as resolved").build(),
                                EventTypeRegistration.write("PEOPLE_TIME_ENTRY_EXCEPTION_WAIVE",
                                                "Waive an exception with a reason").build(),

                                // UserPersonLinkController - 2 events
                                EventTypeRegistration.write("PEOPLE_USER_PERSON_LINK_CREATE",
                                                "Link user to person").build(),
                                EventTypeRegistration.write("PEOPLE_USER_PERSON_LINK_DELETE",
                                                "Unlink user from person").build(),

                                // PersonAccessController - 4 events
                                EventTypeRegistration.fastRead("PEOPLE_ACCESS_ROLES_LIST",
                                                "List available access roles for people").build(),
                                EventTypeRegistration.fastRead("PEOPLE_ACCESS_ASSIGNMENTS_LIST",
                                                "List role assignments for a person").build(),
                                EventTypeRegistration.write("PEOPLE_ACCESS_ASSIGNMENT_CREATE",
                                                "Create role assignment for a person").build(),
                                EventTypeRegistration.write("PEOPLE_ACCESS_ASSIGNMENT_REVOKE",
                                                "Revoke role assignment for a person").build());
        }
}
