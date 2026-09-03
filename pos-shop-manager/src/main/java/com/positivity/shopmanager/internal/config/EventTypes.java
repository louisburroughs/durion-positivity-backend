package com.positivity.shopmanager.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/**
 * Registry of all event types emitted by the pos-shop-manager module.
 * Each event type is registered with appropriate performance thresholds
 * based on expected operation latency characteristics.
 */
public final class EventTypes {

    private EventTypes() {
        // Utility class
    }

    /**
     * All event type registrations for the shop manager module.
     * Total: 19 event types.
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                // ShopBayController - 3 events
                EventTypeRegistration.write("SHOP_BAY_CREATE", "Create a new bay for a specific shop location")
                        .build(),
                EventTypeRegistration.write("SHOP_BAY_MANAGE", "Create or update bays in bulk")
                        .build(),
                EventTypeRegistration.write("SHOP_BAY_DELETE", "Delete a bay from a specific shop location")
                        .build(),

                // ShopMobileUnitController - 3 events
                EventTypeRegistration.write(
                                "SHOP_MOBILE_UNIT_CREATE", "Create a new mobile unit for a specific shop location")
                        .build(),
                EventTypeRegistration.write("SHOP_MOBILE_UNIT_MANAGE", "Create or update mobile units in bulk")
                        .build(),
                EventTypeRegistration.write(
                                "SHOP_MOBILE_UNIT_DELETE", "Delete a mobile unit from a specific shop location")
                        .build(),
                EventTypeRegistration.write("SHOP_MECHANIC_SKILLS_REPLACE", "Replace a mechanic's skill set")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("SHOP_MECHANIC_SKILLS_BULK_INGEST", "Bulk set mechanics' skill sets")
                        .apiVersion("1")
                        .build(),

                // AppointmentsController - 5 events
                EventTypeRegistration.write("SHOPMGR_APPOINTMENT_CREATE", "Create an appointment")
                        .build(),
                EventTypeRegistration.write("SHOPMGR_APPOINTMENT_CREATED", "Appointment created domain event")
                        .build(),
                EventTypeRegistration.write("SHOPMGR_APPOINTMENT_RESCHEDULE", "Reschedule an appointment")
                        .build(),
                EventTypeRegistration.write("SHOPMGR_APPOINTMENT_CANCEL", "Cancel an appointment")
                        .build(),
                EventTypeRegistration.fastRead(
                                "SHOPMGR_SCHEDULE_VIEW", "View schedule by location and resource filters")
                        .build(),
                // ConflictOverrideController - 1 event
                EventTypeRegistration.write(
                                "SHOPMGR_APPOINTMENT_CONFLICT_OVERRIDE_CREATE",
                                "Override appointment scheduling conflict with manager permission")
                        .build(),
                // AssignmentController - 2 events
                EventTypeRegistration.write(
                                "SHOPMGR_ASSIGNMENT_CREATED",
                                "Mechanic and resource assignment created for an appointment")
                        .build(),
                EventTypeRegistration.fastRead(
                                "SHOPMGR_ASSIGNMENT_LIST_FETCHED",
                                "List mechanic and resource assignments for an appointment")
                        .build(),
                // AppointmentsController source-linked events (CAP-249 Story #12) - 2 events
                EventTypeRegistration.write(
                                "SHOPMGR_APPOINTMENT_CREATED_FROM_ESTIMATE",
                                "Appointment created from an approved estimate; WorkExec notified")
                        .build(),
                EventTypeRegistration.write(
                                "SHOPMGR_APPOINTMENT_CREATED_FROM_WORKORDER",
                                "Appointment created from a work order; WorkExec notified")
                        .build(),
                // TechnicianController - 1 event (#885)
                EventTypeRegistration.fastRead(
                                "SHOPMGR_TECHNICIAN_PERSON_GET",
                                "Get technician person details from the people-contact replica")
                        .build(),
                // MechanicRosterController - 1 event (#1648)
                EventTypeRegistration.search(
                                "SHOPMGR_MECHANIC_ROSTER_LIST",
                                "List the HR-synchronized mechanic roster with status and skill filters")
                        .apiVersion("1")
                        .build(),
                // TechnicianController roster query - 1 event (#1648)
                EventTypeRegistration.search(
                                "SHOPMGR_LOCATION_TECHNICIAN_LIST",
                                "List technicians assigned to a shop location with status and skill filters")
                        .apiVersion("1")
                        .build(),
                // ShopDashboardController - 1 event (#1658)
                EventTypeRegistration.search(
                                "SHOPMGR_SHOP_DASHBOARD_VIEW",
                                "View the aggregate shop dashboard: units, their workorders, and open work")
                        .apiVersion("1")
                        .build());
    }
}
