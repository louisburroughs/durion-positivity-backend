package com.positivity.inventory.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types emitted by the pos-inventory module.
 * Each event type is registered with appropriate performance thresholds
 * based on expected operation latency characteristics.
 */
public final class EventTypes {

        private EventTypes() {
                // Utility class
        }

        /**
         * All event type registrations for the inventory module.
         * Total: 37 event types (verified current registry count).
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
                                EventTypeRegistration.fastRead("INVENTORY_LEAD_TIME_QUERY",
                                                "Query dynamic lead time by productId and locationId")
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
                                                "Cross-dock receiving line directly to workorder").build(),

                                // PurchaseOrderController - 9 events
                                EventTypeRegistration.write("INVENTORY_PURCHASE_ORDER_CREATE",
                                                "Create a purchase order").build(),
                                EventTypeRegistration.fastRead("INVENTORY_PURCHASE_ORDER_GET",
                                                "Get purchase order").build(),
                                EventTypeRegistration.fastRead("INVENTORY_PURCHASE_ORDER_LIST",
                                                "List purchase orders").build(),
                                EventTypeRegistration.write("INVENTORY_PURCHASE_ORDER_APPROVE",
                                                "Approve a purchase order").build(),
                                EventTypeRegistration.approval("INVENTORY_PURCHASE_ORDER_ENCUMBRANCE",
                                                "Emit encumbrance posting contract on PO approval").build(),
                                EventTypeRegistration.write("INVENTORY_PURCHASE_ORDER_ACCOUNTING_ERROR",
                                                "Record accounting error on PO GL posting failure").build(),
                                EventTypeRegistration.write("INVENTORY_PURCHASE_ORDER_RECEIVE",
                                                "Record a receipt against a purchase order").build(),
                                EventTypeRegistration.write("INVENTORY_PURCHASE_ORDER_REVISE",
                                                "Revise a purchase order").build(),
                                EventTypeRegistration.write("INVENTORY_PURCHASE_ORDER_CANCEL",
                                                "Cancel a purchase order").build(),

                                // AsnController - 5 events
                                EventTypeRegistration.write("INVENTORY_ASN_CREATE",
                                                "Create an Advance Shipping Notice").build(),
                                EventTypeRegistration.fastRead("INVENTORY_ASN_GET",
                                                "Get ASN by ID").build(),
                                EventTypeRegistration.write("INVENTORY_ASN_GOODS_RECEIPT_CREATED",
                                                "Publish goods receipt intent event before receipt line processing")
                                                .build(),
                                EventTypeRegistration.write("INVENTORY_GOODS_RECEIPT_CREATE",
                                                "Create a goods receipt").build(),
                                EventTypeRegistration.fastRead("INVENTORY_GOODS_RECEIPT_GET",
                                                "Get goods receipt by ID").build(),

                                // ReallocationController - 1 event
                                EventTypeRegistration.write("INVENTORY_ALLOCATION_REALLOCATE",
                                                "Trigger deterministic reallocation of reserved stock by priority")
                                                .build(),

                                // ShortageController - 1 event
                                EventTypeRegistration.write("INVENTORY_SHORTAGE_RESOLVE",
                                                "Resolve inventory shortage with substitute or backorder options")
                                                .build());
        }
}
