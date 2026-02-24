package com.positivity.inventory.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types emitted by the pos-inventory module.
 * Each event type is registered with appropriate performance thresholds
 * based on expected operation latency characteristics.
 */
public final class InventoryEventTypes {

        private InventoryEventTypes() {
                // Utility class
        }

        /**
         * All event type registrations for the inventory module.
         * Total: 12 event types.
         */
        public static List<EventTypeRegistration> all() {
                return List.of(
                                // CycleCountAdjustmentController - 3 events
                                EventTypeRegistration.write("INVENTORY_CYCLE_COUNT_ADJUSTMENT_CREATE",
                                                "Create a new cycle count adjustment").build(),
                                EventTypeRegistration.approval("INVENTORY_CYCLE_COUNT_ADJUSTMENT_APPROVE",
                                                "Approve a pending cycle count adjustment").build(),
                                EventTypeRegistration.approval("INVENTORY_CYCLE_COUNT_ADJUSTMENT_REJECT",
                                                "Reject a pending cycle count adjustment").build(),

                                // CycleCountController - 2 events
                                EventTypeRegistration.write("INVENTORY_CYCLE_COUNT_SUBMIT",
                                                "Submit a count for a cycle count task").build(),
                                EventTypeRegistration.write("INVENTORY_CYCLE_COUNT_RECOUNT",
                                                "Submit a recount for a cycle count task").build(),

                                // InventoryAvailabilityController - 1 event
                                EventTypeRegistration.write("INVENTORY_AVAILABILITY_UPDATE",
                                                "Update inventory availability for a product").build(),

                                // PickingListController - 1 event
                                EventTypeRegistration.write("INVENTORY_PICKING_LIST_CONFIRM",
                                                "Confirm a picking list and commit consumption").build(),

                                // InventorySiteDefaultLocationsController - 1 event
                                EventTypeRegistration.write("INVENTORY_SITE_DEFAULT_LOCATIONS_UPDATE",
                                                "Replace site default locations configuration").build(),

                                // InventoryLocationDeactivationController - 1 event
                                EventTypeRegistration.write("INVENTORY_LOCATION_DEACTIVATE",
                                                "Deactivate a storage location with optional stock transfer").build(),

                                // StockMovementController - 3 events
                                EventTypeRegistration.write("INVENTORY_STOCK_MOVEMENT_CREATE",
                                                "Record an inventory stock movement in the ledger").build(),
                                EventTypeRegistration.write("INVENTORY_ADJUSTMENT_REQUEST_CREATE",
                                                "Create a pending inventory adjustment request").build(),
                                EventTypeRegistration.approval("INVENTORY_ADJUSTMENT_REQUEST_APPROVE",
                                                "Approve and post inventory adjustment request to ledger").build());
        }
}
