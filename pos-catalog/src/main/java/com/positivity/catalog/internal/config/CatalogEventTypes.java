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
                                                "Create a new catalog item (product, service, or non-inventory)")
                                                .build(),
                                EventTypeRegistration.write("CATALOG_ITEM_UPDATE",
                                                "Update an existing catalog item").build(),
                                EventTypeRegistration.write("CATALOG_CATALOG_CREATE",
                                                "Create a new catalog").build(),
                                EventTypeRegistration.write("CATALOG_CATALOG_UPDATE",
                                                "Update an existing catalog").build(),
                                EventTypeRegistration.fastRead("CATALOG_PRODUCT_LIFECYCLE_GET",
                                                "Get product lifecycle and replacement suggestions").build(),
                                EventTypeRegistration.write("CATALOG_PRODUCT_LIFECYCLE_UPDATE",
                                                "Set product lifecycle state with effective date").build(),
                                EventTypeRegistration.write("CATALOG_PRODUCT_REPLACEMENT_ADD",
                                                "Add replacement recommendation for a discontinued product").build(),
                                EventTypeRegistration.write("CATALOG_SUPPLIER_COST_CREATE",
                                                "Create supplier item cost structure").build(),
                                EventTypeRegistration.write("CATALOG_SUPPLIER_COST_UPDATE",
                                                "Update supplier item cost structure").build(),
                                EventTypeRegistration.write("CATALOG_SUPPLIER_COST_DELETE",
                                                "Delete supplier item cost structure").build(),
                                EventTypeRegistration.write("CATALOG_ITEM_COST_STANDARD_UPDATE",
                                                "Manually update item standard cost").build());
        }
}
