package com.positivity.catalog.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types emitted by the pos-catalog module.
 */
public final class CatalogEventTypes {

    private CatalogEventTypes() {
        // Utility class
    }

    /**
     * All event type registrations for the catalog module.
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                EventTypeRegistration.write("CATALOG_ITEM_CREATE",
                        "Create a new catalog item (product, service, or non-inventory)").build(),
                EventTypeRegistration.write("CATALOG_ITEM_UPDATE",
                        "Update an existing catalog item").build(),
                EventTypeRegistration.write("CATALOG_CATALOG_CREATE",
                        "Create a new catalog").build(),
                EventTypeRegistration.write("CATALOG_CATALOG_UPDATE",
                        "Update an existing catalog").build());
    }
}
