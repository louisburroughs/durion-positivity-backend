package com.positivity.shopmanager.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types emitted by the pos-shop-manager module.
 * Each event type is registered with appropriate performance thresholds
 * based on expected operation latency characteristics.
 */
public final class ShopEventTypes {

        private ShopEventTypes() {
                // Utility class
        }

        /**
         * All event type registrations for the shop manager module.
         * Total: 7 event types.
         */
        public static List<EventTypeRegistration> all() {
                return List.of(
                                // ShopBayController - 2 events
                                EventTypeRegistration.write("SHOP_BAY_CREATE",
                                                "Create a new bay for a specific shop location").build(),
                                EventTypeRegistration.write("SHOP_BAY_MANAGE",
                                                "Create or update bays in bulk").build(),
                                EventTypeRegistration.write("SHOP_BAY_DELETE",
                                                "Delete a bay from a specific shop location").build(),

                                // ShopMobileUnitController - 2 events
                                EventTypeRegistration.write("SHOP_MOBILE_UNIT_CREATE",
                                                "Create a new mobile unit for a specific shop location").build(),
                                EventTypeRegistration.write("SHOP_MOBILE_UNIT_MANAGE",
                                                "Create or update mobile units in bulk").build(),
                                EventTypeRegistration.write("SHOP_MOBILE_UNIT_DELETE",
                                                "Delete a mobile unit from a specific shop location").build(),

                                // AppointmentsController - 1 event
                                EventTypeRegistration.write("SHOPMGR_APPOINTMENT_CREATE",
                                                "Create an appointment").build(),
                                EventTypeRegistration.write("SHOPMGR_APPOINTMENT_CREATED",
                                                "Appointment created domain event").build(),
                                EventTypeRegistration.write("SHOPMGR_APPOINTMENT_RESCHEDULE",
                                                "Reschedule an appointment").build(),
                                EventTypeRegistration.write("SHOPMGR_APPOINTMENT_CANCEL",
                                                "Cancel an appointment").build(),
                                EventTypeRegistration.fastRead("SHOPMGR_SCHEDULE_VIEW",
                                                "View schedule by location and resource filters").build());
        }
}
