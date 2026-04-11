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
         * Total: 16 event types.
         */
        public static List<EventTypeRegistration> all() {
                return List.of(
                                // ShopBayController - 3 events
                                EventTypeRegistration.write("SHOP_BAY_CREATE",
                                                "Create a new bay for a specific shop location").build(),
                                EventTypeRegistration.write("SHOP_BAY_MANAGE",
                                                "Create or update bays in bulk").build(),
                                EventTypeRegistration.write("SHOP_BAY_DELETE",
                                                "Delete a bay from a specific shop location").build(),

                                // ShopMobileUnitController - 3 events
                                EventTypeRegistration.write("SHOP_MOBILE_UNIT_CREATE",
                                                "Create a new mobile unit for a specific shop location").build(),
                                EventTypeRegistration.write("SHOP_MOBILE_UNIT_MANAGE",
                                                "Create or update mobile units in bulk").build(),
                                EventTypeRegistration.write("SHOP_MOBILE_UNIT_DELETE",
                                                "Delete a mobile unit from a specific shop location").build(),

                                // AppointmentsController - 5 events
                                EventTypeRegistration.write("SHOPMGR_APPOINTMENT_CREATE",
                                                "Create an appointment").build(),
                                EventTypeRegistration.write("SHOPMGR_APPOINTMENT_CREATED",
                                                "Appointment created domain event").build(),
                                EventTypeRegistration.write("SHOPMGR_APPOINTMENT_RESCHEDULE",
                                                "Reschedule an appointment").build(),
                                EventTypeRegistration.write("SHOPMGR_APPOINTMENT_CANCEL",
                                                "Cancel an appointment").build(),
                                EventTypeRegistration.fastRead("SHOPMGR_SCHEDULE_VIEW",
                                                "View schedule by location and resource filters").build(),
                                // ConflictOverrideController - 1 event
                                EventTypeRegistration.write("SHOPMGR_APPOINTMENT_CONFLICT_OVERRIDE_CREATE",
                                                "Override appointment scheduling conflict with manager permission")
                                                .build(),
                                // AssignmentController - 2 events
                                EventTypeRegistration.write("SHOPMGR_ASSIGNMENT_CREATED",
                                                "Mechanic and resource assignment created for an appointment").build(),
                                EventTypeRegistration.fastRead("SHOPMGR_ASSIGNMENT_LIST_FETCHED",
                                                "List mechanic and resource assignments for an appointment").build(),
                                // AppointmentsController source-linked events (CAP-249 Story #12) - 2 events
                                EventTypeRegistration.write("SHOPMGR_APPOINTMENT_CREATED_FROM_ESTIMATE",
                                                "Appointment created from an approved estimate; WorkExec notified")
                                                .build(),
                                EventTypeRegistration.write("SHOPMGR_APPOINTMENT_CREATED_FROM_WORKORDER",
                                                "Appointment created from a work order; WorkExec notified").build());
        }
}
