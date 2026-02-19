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
                                EventTypeRegistration.write("CATALOG_PRODUCT_CREATED",
                                                "Create product master record").build(),
                                EventTypeRegistration.write("CATALOG_PRODUCT_UPDATED",
                                                "Update product master record").build(),
                                EventTypeRegistration.write("CATALOG_PRODUCT_STATUS_CHANGED",
                                                "Change product operational status").build(),
                                EventTypeRegistration.write("CATALOG_PRODUCT_LIFECYCLE_CHANGED",
                                                "Set product lifecycle state").build(),
                                EventTypeRegistration.write("CATALOG_PRODUCT_REPLACEMENT_ADD",
                                                "Add replacement recommendation for a discontinued product").build(),
                                EventTypeRegistration.write("CATALOG_UOM_CONVERSION_CREATE",
                                                "Create UOM conversion").build(),
                                EventTypeRegistration.write("CATALOG_UOM_CONVERSION_UPDATE",
                                                "Update UOM conversion factor").build(),
                                EventTypeRegistration.write("CATALOG_UOM_CONVERSION_DEACTIVATE",
                                                "Deactivate UOM conversion").build(),
                                EventTypeRegistration.write("CATALOG_SUPPLIER_COST_CREATE",
                                                "Create supplier item cost structure").build(),
                                EventTypeRegistration.write("CATALOG_SUPPLIER_COST_UPDATE",
                                                "Update supplier item cost structure").build(),
                                EventTypeRegistration.write("CATALOG_SUPPLIER_COST_DELETE",
                                                "Delete supplier item cost structure").build(),
                                EventTypeRegistration.write("CATALOG_ITEM_COST_STANDARD_UPDATE",
                                                "Manually update item standard cost").build(),
                                EventTypeRegistration.write("CATALOG_GUARDRAIL_POLICY_UPSERT",
                                                "Create or update location guardrail policy for price overrides")
                                                .build(),
                                EventTypeRegistration.write("CATALOG_LOCATION_OVERRIDE_CREATE",
                                                "Create location-specific price override with guardrail checks")
                                                .build(),
                                EventTypeRegistration.approval("CATALOG_LOCATION_OVERRIDE_APPROVE",
                                                "Approve pending location price override and activate effective price")
                                                .build(),
                                EventTypeRegistration.approval("CATALOG_LOCATION_OVERRIDE_REJECT",
                                                "Reject pending location price override with reason metadata").build(),
                                EventTypeRegistration.write("CATALOG_SUPPLIER_ITEM_COST_CREATE",
                                                "Create supplier-item cost structure with optional volume tiers")
                                                .build(),
                                EventTypeRegistration.write("CATALOG_SUPPLIER_ITEM_COST_UPDATE",
                                                "Update supplier-item cost structure and volume tiers").build(),
                                EventTypeRegistration.write("CATALOG_SUPPLIER_ITEM_COST_DELETE",
                                                "Delete supplier-item cost structure and all tiers").build(),
                                EventTypeRegistration.write("CATALOG_MSRP_CREATE", "Create MSRP record").build(),
                                EventTypeRegistration.write("CATALOG_MSRP_UPDATE", "Update MSRP record").build(),
                                EventTypeRegistration.write("CATALOG_PRICE_BOOK_CREATE", "Create price book").build(),
                                EventTypeRegistration.write("CATALOG_PRICE_BOOK_UPDATE", "Update price book").build(),
                                EventTypeRegistration.write("CATALOG_PRICE_BOOK_RULE_CREATE", "Create price book rule")
                                                .build(),
                                EventTypeRegistration.write("CATALOG_PRICE_BOOK_RULE_UPDATE", "Update price book rule")
                                                .build(),
                                EventTypeRegistration.write("CATALOG_PRICE_BOOK_RULE_DEACTIVATE",
                                                "Deactivate price book rule")
                                                .build(),
                                EventTypeRegistration.fastRead("CATALOG_PRICE_BOOK_RESOLVE_PRICE",
                                                "Resolve effective price").build());
        }
}
