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
         * Total: 20 event types.
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

                                // InventoryAvailabilityController - 2 events
                                EventTypeRegistration.write("INVENTORY_AVAILABILITY_UPDATE",
                                                "Update inventory availability for a product").build(),
                                EventTypeRegistration.fastRead("INVENTORY_AVAILABILITY_QUERY",
                                                "Query on-hand and ATP availability by productSku and locationId")
                                                .build(),

                                // PickingListController - 1 event
                                EventTypeRegistration.write("INVENTORY_PICKING_LIST_CONFIRM",
                                                "Confirm a picking list and commit consumption").build(),

                                // InventorySiteDefaultLocationsController - 1 event
                                EventTypeRegistration.write("INVENTORY_SITE_DEFAULT_LOCATIONS_UPDATE",
                                                "Replace site default locations configuration").build(),

                                // InventoryLocationDeactivationController - 1 event
                                EventTypeRegistration.write("INVENTORY_LOCATION_DEACTIVATE",
                                                "Deactivate a storage location with optional stock transfer").build(),

                                // PutawayController - 2 events
                                EventTypeRegistration.write("INVENTORY_PUTAWAY_TASK_GENERATE",
                                                "Generate putaway tasks for received inventory lines").build(),
                                EventTypeRegistration.write("INVENTORY_PUTAWAY_TASK_CLAIM",
                                                "Claim a putaway task for execution").build(),

                                // ReplenishmentController - 1 event
                                EventTypeRegistration.write("INVENTORY_REPLENISHMENT_POLICY_CREATE",
                                                "Create replenishment policy used for task generation").build(),

                                // StockMovementController - 3 events
                                EventTypeRegistration.write("INVENTORY_STOCK_MOVEMENT_CREATE",
                                                "Record an inventory stock movement in the ledger").build(),
                                EventTypeRegistration.write("INVENTORY_ADJUSTMENT_REQUEST_CREATE",
                                                "Create a pending inventory adjustment request").build(),
                                EventTypeRegistration.approval("INVENTORY_ADJUSTMENT_REQUEST_APPROVE",
                                                "Approve and post inventory adjustment request to ledger").build(),

                                // ReceivingController - 4 events
                                EventTypeRegistration.write("INVENTORY_RECEIVING_SESSION_CREATE",
                                                "Create a receiving session from a PO or ASN").build(),
                                EventTypeRegistration.fastRead("INVENTORY_RECEIVING_SESSION_GET",
                                                "Get a receiving session by ID").build(),
                                EventTypeRegistration.write("INVENTORY_RECEIVING_SESSION_COMPLETE",
                                                "Complete receive items into staging").build(),
                                EventTypeRegistration.write("INVENTORY_RECEIVING_CROSSDOCK",
                                                "Cross-dock receiving line directly to workorder").build());
        }
}
