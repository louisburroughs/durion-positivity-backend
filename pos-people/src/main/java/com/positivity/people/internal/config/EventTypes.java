package com.positivity.people.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/**
 * Registry of all event types emitted by the pos-people module. Each event type
 * is
 * registered with appropriate performance thresholds based on expected
 * operation latency
 * characteristics.
 */
public final class EventTypes {

    private EventTypes() {
        // Utility class
    }

    /**
     * All event type registrations for the people HR module (person/link/access events moved
     * to pos-people-contact, #874/#875).
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                // PersonBulkIngestController (person CRUD moved to pos-people-contact, #874/#875)
                EventTypeRegistration.write("PEOPLE_BULK_INGEST", "Bulk import employee records")
                        .build(),

                // StaffingAssignmentController - 3 events
                EventTypeRegistration.write(
                                "PEOPLE_STAFFING_ASSIGNMENT_CREATE", "Create a person-to-location staffing assignment")
                        .build(),
                EventTypeRegistration.write("PEOPLE_STAFFING_ASSIGNMENT_UPDATE", "Update a staffing assignment")
                        .build(),
                EventTypeRegistration.write("PEOPLE_STAFFING_ASSIGNMENT_END", "End a staffing assignment")
                        .build(),

                // EmployeeController - 4 events
                EventTypeRegistration.write("PEOPLE_EMPLOYEE_CREATE", "Create employee profile")
                        .build(),
                EventTypeRegistration.fastRead("PEOPLE_EMPLOYEE_GET", "Get employee profile")
                        .build(),
                EventTypeRegistration.write("PEOPLE_EMPLOYEE_UPDATE", "Update employee profile")
                        .build(),
                EventTypeRegistration.write("PEOPLE_EMPLOYEE_DISABLE", "Disable employee profile")
                        .build(),

                // WorkSessionController - 4 events
                EventTypeRegistration.write("PEOPLE_WORK_SESSION_START", "Start a work session for a person")
                        .build(),
                EventTypeRegistration.write("PEOPLE_WORK_SESSION_STOP", "Stop an active work session")
                        .build(),
                EventTypeRegistration.write(
                                "PEOPLE_WORK_SESSION_BREAK_START", "Start a break within an active work session")
                        .build(),
                EventTypeRegistration.write("PEOPLE_WORK_SESSION_BREAK_STOP", "End a break within a work session")
                        .build(),

                // TimeEntryAdjustmentController - 2 events
                EventTypeRegistration.write(
                                "PEOPLE_TIME_ENTRY_ADJUSTMENT_CREATE", "Create a time entry adjustment request")
                        .build(),
                EventTypeRegistration.approval(
                                "PEOPLE_TIME_ENTRY_ADJUSTMENT_APPROVE", "Approve a pending time entry adjustment")
                        .build(),

                // TimeEntryApprovalController - 2 events
                EventTypeRegistration.approval("PEOPLE_TIME_ENTRY_APPROVE", "Batch approve time entries")
                        .build(),
                EventTypeRegistration.approval("PEOPLE_TIME_ENTRY_REJECT", "Batch reject time entries")
                        .build(),

                // TimeEntryExceptionController - 4 events
                EventTypeRegistration.write(
                                "PEOPLE_TIME_ENTRY_EXCEPTION_CREATE", "Create a time entry exception record")
                        .build(),
                EventTypeRegistration.write("PEOPLE_TIME_ENTRY_EXCEPTION_ACKNOWLEDGE", "Acknowledge an exception")
                        .build(),
                EventTypeRegistration.write("PEOPLE_TIME_ENTRY_EXCEPTION_RESOLVE", "Mark an exception as resolved")
                        .build(),
                EventTypeRegistration.write("PEOPLE_TIME_ENTRY_EXCEPTION_WAIVE", "Waive an exception with a reason")
                        .build(),

                // PeopleAvailabilityController - 5 events
                EventTypeRegistration.fastRead(
                                "PEOPLE_AVAILABILITY_LIST",
                                "List people availability with optional location/date filters")
                        .build(),
                EventTypeRegistration.fastRead(
                                "PEOPLE_PRIMARY_LOCATION_GET",
                                "Get current user primary location from staffing assignments")
                        .build(),
                EventTypeRegistration.fastRead(
                                "PEOPLE_ME_LOCATIONS_LIST", "List current user's active location assignments")
                        .build(),
                EventTypeRegistration.fastRead(
                                "PEOPLE_PERSON_LOCATIONS_LIST", "List a person's active location assignments")
                        .build(),
                EventTypeRegistration.fastRead(
                                "PEOPLE_PERSON_PRIMARY_LOCATION_GET",
                                "Get a person's primary location from staffing assignments")
                        .build(),

                // PeopleReportsController - 2 events
                EventTypeRegistration.search(
                                "REPORT_ATTENDANCE_VS_JOBTIME_GENERATED",
                                "Generate attendance vs job-time discrepancy report")
                        .build(),
                EventTypeRegistration.search(
                                "PEOPLE_TIME_APPROVED_EXPORT_READ",
                                "Read approved time rows for accounting export orchestration")
                        .build());
    }
}
