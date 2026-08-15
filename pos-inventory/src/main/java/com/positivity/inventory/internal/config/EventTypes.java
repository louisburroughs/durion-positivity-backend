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
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                // CycleCountAdjustmentController - 3 events
                EventTypeRegistration.write(
                                "INVENTORY_CYCLE_COUNT_ADJUSTMENT_CREATE", "Create a new cycle count adjustment")
                        .build(),
                EventTypeRegistration.approval(
                                "INVENTORY_CYCLE_COUNT_ADJUSTMENT_APPROVE", "Approve a pending cycle count adjustment")
                        .build(),
                EventTypeRegistration.approval(
                                "INVENTORY_CYCLE_COUNT_ADJUSTMENT_REJECT", "Reject a pending cycle count adjustment")
                        .build(),

                // ScrapController - 3 events (odoo-parity D1, #1030)
                EventTypeRegistration.write("INVENTORY_SCRAP_CREATE", "Create a scrap (write-off) document")
                        .build(),
                EventTypeRegistration.approval("INVENTORY_SCRAP_APPROVE", "Approve a pending scrap document")
                        .build(),
                EventTypeRegistration.approval("INVENTORY_SCRAP_REJECT", "Reject a pending scrap document")
                        .build(),

                // TransferOrderController - 6 events (odoo-parity C1/C2/C3, #1035/#1036/#1039)
                EventTypeRegistration.write("INVENTORY_TRANSFER_ORDER_CREATE", "Create a cross-site transfer order")
                        .build(),
                EventTypeRegistration.approval("INVENTORY_TRANSFER_ORDER_APPROVE", "Approve a DRAFT transfer order")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_TRANSFER_ORDER_CANCEL", "Cancel a transfer order before dispatch")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_TRANSFER_ORDER_DISPATCH",
                                "Dispatch a transfer order (posts TRANSFER_OUT into transit)")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_TRANSFER_ORDER_RECEIVE",
                                "Receive dispatched transfer quantities at the destination (posts TRANSFER_IN)")
                        .build(),
                // Approval preset: short-close accepts a stock loss or reverses a movement
                // (odoo-parity C3, #1039).
                EventTypeRegistration.approval(
                                "INVENTORY_TRANSFER_ORDER_SHORT_CLOSE",
                                "Short-close a transfer order's undelivered remainder with a loss or return"
                                        + " disposition")
                        .build(),

                // SourcingStrategyController - 3 events (odoo-parity H1, #1037)
                EventTypeRegistration.fastRead(
                                "INVENTORY_SOURCING_STRATEGY_LIST", "List sourcing strategy configurations")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_SOURCING_STRATEGY_UPSERT",
                                "Create or update the sourcing strategy for one scope")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_SOURCING_STRATEGY_DEACTIVATE",
                                "Deactivate a sourcing strategy configuration row")
                        .build(),

                // CostingMethodController - 2 events (odoo-parity J1, #1048)
                EventTypeRegistration.fastRead("INVENTORY_VALUATION_METHOD_LIST", "List costing method configurations")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_VALUATION_METHOD_UPSERT",
                                "Create or update the costing method for one scope")
                        .build(),

                // RevaluationController - 3 events (odoo-parity J4, #1054)
                EventTypeRegistration.write(
                                "INVENTORY_REVALUATION_CREATE",
                                "Submit a manual cost revaluation (auto-applies below threshold)")
                        .build(),
                EventTypeRegistration.approval(
                                "INVENTORY_REVALUATION_APPROVE",
                                "Approve a pending cost revaluation and restate the SKU cost state")
                        .build(),
                EventTypeRegistration.approval("INVENTORY_REVALUATION_REJECT", "Reject a pending cost revaluation")
                        .build(),

                // CycleCountController - 2 events
                EventTypeRegistration.write("INVENTORY_CYCLE_COUNT_SUBMIT", "Submit a count for a cycle count task")
                        .build(),
                EventTypeRegistration.write("INVENTORY_CYCLE_COUNT_RECOUNT", "Submit a recount for a cycle count task")
                        .build(),

                // CycleCountPlanController - 3 events
                EventTypeRegistration.write("INVENTORY_CYCLE_COUNT_PLAN_CREATE", "Create a cycle count plan")
                        .build(),
                EventTypeRegistration.fastRead("INVENTORY_CYCLE_COUNT_PLAN_LIST", "List cycle count plans")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_CYCLE_COUNT_PLAN_STATUS_UPDATE", "Transition a cycle count plan status")
                        .build(),

                // CycleCountScheduleController - 4 events (odoo-parity I1, #1031)
                EventTypeRegistration.write(
                                "INVENTORY_CYCLE_COUNT_SCHEDULE_CREATE", "Create a recurring cycle count schedule")
                        .build(),
                EventTypeRegistration.fastRead(
                                "INVENTORY_CYCLE_COUNT_SCHEDULE_LIST", "List recurring cycle count schedules")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_CYCLE_COUNT_SCHEDULE_UPDATE", "Update a recurring cycle count schedule")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_CYCLE_COUNT_SCHEDULE_DEACTIVATE",
                                "Deactivate a recurring cycle count schedule")
                        .build(),

                // InventoryAvailabilityController - 2 events
                EventTypeRegistration.write(
                                "INVENTORY_AVAILABILITY_UPDATE", "Update inventory availability for a product")
                        .build(),

                // PickingListController - 1 event
                EventTypeRegistration.write(
                                "INVENTORY_PICKING_LIST_CONFIRM", "Confirm a picking list and commit consumption")
                        .build(),

                // InventorySiteDefaultLocationsController - 1 event
                EventTypeRegistration.write(
                                "INVENTORY_SITE_DEFAULT_LOCATIONS_UPDATE",
                                "Replace site default locations configuration")
                        .build(),

                // InventoryLocationDeactivationController - 1 event
                EventTypeRegistration.write(
                                "INVENTORY_LOCATION_DEACTIVATE",
                                "Deactivate a storage location with optional stock transfer")
                        .build(),

                // LocationSyncController - 3 events
                EventTypeRegistration.write(
                                "INVENTORY_LOCATION_SYNC_TRIGGER", "Trigger a location roster sync from pos-location")
                        .build(),
                EventTypeRegistration.fastRead("INVENTORY_LOCATION_SYNC_LOG_LIST", "List location sync audit logs")
                        .build(),
                EventTypeRegistration.fastRead("INVENTORY_LOCATION_SYNC_LOG_GET", "Get a location sync audit log entry")
                        .build(),

                // PutawayController - 2 events
                EventTypeRegistration.write(
                                "INVENTORY_PUTAWAY_TASK_GENERATE",
                                "Generate putaway tasks for received inventory lines")
                        .build(),
                EventTypeRegistration.write("INVENTORY_PUTAWAY_TASK_CLAIM", "Claim a putaway task for execution")
                        .build(),

                // ReplenishmentController - 5 events
                EventTypeRegistration.write(
                                "INVENTORY_REPLENISHMENT_POLICY_CREATE",
                                "Create replenishment policy used for task generation")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_REPLENISHMENT_POLICY_UPDATE",
                                "Update a replenishment policy's thresholds and tuning fields")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_REPLENISHMENT_POLICY_SNOOZE", "Snooze or unsnooze a replenishment policy")
                        .build(),
                EventTypeRegistration.fastRead(
                                "INVENTORY_REPLENISHMENT_NEEDS_LIST",
                                "List the side-effect-free replenishment needs report")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_REPLENISHMENT_SCAN_RUN",
                                "Run the batch replenishment scan over all replenishment policies")
                        .build(),

                // PurchaseSuggestionController - 3 events (odoo-parity F4, #1044)
                EventTypeRegistration.write(
                                "INVENTORY_PURCHASE_SUGGESTION_ACCEPT",
                                "Accept a purchase suggestion (human-mandatory gate, plan D-3)")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_PURCHASE_SUGGESTION_DISMISS",
                                "Dismiss a purchase suggestion with a mandatory reason")
                        .build(),
                // Approval preset: conversion opens spend (a DRAFT PO) against the PO domain.
                EventTypeRegistration.approval(
                                "INVENTORY_PURCHASE_SUGGESTION_CONVERT",
                                "Convert accepted purchase suggestions into a single DRAFT purchase order")
                        .build(),

                // StockMovementController - 3 events
                EventTypeRegistration.write(
                                "INVENTORY_STOCK_MOVEMENT_CREATE", "Record an inventory stock movement in the ledger")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_ADJUSTMENT_REQUEST_CREATE", "Create a pending inventory adjustment request")
                        .build(),
                EventTypeRegistration.approval(
                                "INVENTORY_ADJUSTMENT_REQUEST_APPROVE",
                                "Approve and post inventory adjustment request to ledger")
                        .build(),

                // ReceivingController - 4 events
                EventTypeRegistration.write(
                                "INVENTORY_RECEIVING_SESSION_CREATE", "Create a receiving session from a PO or ASN")
                        .build(),
                EventTypeRegistration.fastRead("INVENTORY_RECEIVING_SESSION_GET", "Get a receiving session by ID")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_RECEIVING_SESSION_COMPLETE", "Complete receive items into staging")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_RECEIVING_CROSSDOCK", "Cross-dock receiving line directly to workorder")
                        .build(),

                // PurchaseOrderController - 9 events
                EventTypeRegistration.write("INVENTORY_PURCHASE_ORDER_CREATE", "Create a purchase order")
                        .build(),
                EventTypeRegistration.fastRead("INVENTORY_PURCHASE_ORDER_GET", "Get purchase order")
                        .build(),
                EventTypeRegistration.fastRead("INVENTORY_PURCHASE_ORDER_LIST", "List purchase orders")
                        .build(),
                EventTypeRegistration.fastRead("INVENTORY_LOT_LIST", "List inventory lots")
                        .build(),
                EventTypeRegistration.fastRead("INVENTORY_LOT_GET", "Get an inventory lot with per-location on-hand")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_LOT_STATUS_UPDATE", "Quarantine, recall, or release an inventory lot")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_LOT_EXPIRATION_SET",
                                "Set or clear an inventory lot's expiration/alert dates")
                        .build(),
                EventTypeRegistration.write("INVENTORY_PURCHASE_ORDER_APPROVE", "Approve a purchase order")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_PURCHASE_ORDER_ACCOUNTING_ERROR",
                                "Record accounting error on PO GL posting failure")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_PURCHASE_ORDER_RECEIVE", "Record a receipt against a purchase order")
                        .build(),
                EventTypeRegistration.write("INVENTORY_PURCHASE_ORDER_REVISE", "Revise a purchase order")
                        .build(),
                EventTypeRegistration.write("INVENTORY_PURCHASE_ORDER_CANCEL", "Cancel a purchase order")
                        .build(),

                // AsnController - 5 events
                EventTypeRegistration.write("INVENTORY_ASN_CREATE", "Create an Advance Shipping Notice")
                        .build(),
                EventTypeRegistration.fastRead("INVENTORY_ASN_GET", "Get ASN by ID")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_ASN_GOODS_RECEIPT_CREATED",
                                "Publish goods receipt intent event before receipt line processing")
                        .build(),
                EventTypeRegistration.write("INVENTORY_GOODS_RECEIPT_CREATE", "Create a goods receipt")
                        .build(),
                EventTypeRegistration.fastRead("INVENTORY_GOODS_RECEIPT_GET", "Get goods receipt by ID")
                        .build(),

                // ReallocationController - 1 event
                EventTypeRegistration.write(
                                "INVENTORY_ALLOCATION_REALLOCATE",
                                "Trigger deterministic reallocation of reserved stock by priority")
                        .build(),

                // ShortageController - 1 event
                EventTypeRegistration.write(
                                "INVENTORY_SHORTAGE_RESOLVE",
                                "Resolve inventory shortage with substitute or backorder options")
                        .build(),
                EventTypeRegistration.write(
                                "INVENTORY_RETURN_SUBMIT_TO_STOCK", "Submit inventory return lines to stock")
                        .build(),
                EventTypeRegistration.write("INVENTORY_BULK_INGEST", "Bulk ingest inventory stock counts")
                        .build(),

                // SupplierStockHintController - 2 events (CAP-322, #1312)
                EventTypeRegistration.fastRead(
                                "INVENTORY_SUPPLIER_STOCK_HINT_LIST_BY_PRODUCT",
                                "List vendor-reported availability hints for a product")
                        .build(),
                EventTypeRegistration.fastRead(
                                "INVENTORY_SUPPLIER_STOCK_HINT_LIST_BY_CODE",
                                "List vendor-reported availability hints for an article code")
                        .build());
    }
}
