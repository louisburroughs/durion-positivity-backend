package com.positivity.shopManager.internal.config;

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
     * Total: 5 event types.
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                // ShopBayController - 2 events
                EventTypeRegistration.write("SHOP_BAY_CREATE",
                        "Create a new bay for a specific shop location").build(),
                EventTypeRegistration.write("SHOP_BAY_MANAGE",
                        "Create or update bays in bulk").build(),

                // ShopMobileUnitController - 2 events
                EventTypeRegistration.write("SHOP_MOBILE_UNIT_CREATE",
                        "Create a new mobile unit for a specific shop location").build(),
                EventTypeRegistration.write("SHOP_MOBILE_UNIT_MANAGE",
                        "Create or update mobile units in bulk").build(),

                // AppointmentsController - 1 event
                EventTypeRegistration.write("SHOP_APPOINTMENT_CREATE",
                        "Create a new appointment for shop scheduling").build());
    }
}
