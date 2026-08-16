package com.positivity.inventory.internal.security;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Inventory Permission Registry
 *
 * Defines all inventory permissions per domain-driven design.
 * Permissions are registered with the central Security Domain at service
 * startup.
 *
 * Permission Format: inventory:resource:action
 * Risk Levels: LOW, MEDIUM, HIGH, CRITICAL
 *
 * References:
 * - Issue #37: Adjustment workflow permissions
 * - Clarification #229: Putaway override permissions
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class InventoryPermissionRegistry {

    // ==================== ADJUSTMENT PERMISSIONS ====================

    /**
     * Create an adjustment request (draft/pending).
     * Captures reason code, quantity, and supporting notes.
     */
    public static final String ADJUSTMENT_CREATE = "inventory:adjustment:create";

    /**
     * Approve and post an adjustment to the ledger.
     * Creates ADJUSTMENT_IN/OUT events.
     */
    public static final String ADJUSTMENT_APPROVE = "inventory:adjustment:approve";

    /**
     * View adjustment history and details.
     */
    public static final String ADJUSTMENT_VIEW = "inventory:adjustment:view";

    // ==================== MOVEMENT PERMISSIONS ====================

    /**
     * Record direct stock movements in the inventory ledger.
     */
    public static final String STOCK_MOVEMENT_CREATE = "inventory:stock_movement:create";

    // ==================== LOCATION PERMISSIONS ====================

    /**
     * View location configuration and site default location assignments.
     */
    public static final String LOCATION_VIEW = "inventory:location:view";

    /**
     * Manage location lifecycle and site default location assignments.
     */
    public static final String LOCATION_ADMIN = "inventory:location:admin";

    // ==================== PICK LIST PERMISSIONS ====================

    /**
     * Create pick lists for workorders.
     */
    public static final String PICK_LIST_CREATE = "inventory:pick_list:create";

    /**
     * View pick lists and pick tasks.
     */
    public static final String PICK_LIST_VIEW = "inventory:pick_list:view";

    /**
     * Execute pick list workflow actions such as release, confirm, and cancel.
     */
    public static final String PICK_LIST_EXECUTE = "inventory:pick_list:execute";

    // ==================== PUTAWAY PERMISSIONS ====================

    /**
     * Generate putaway tasks from received inventory.
     */
    public static final String PUTAWAY_GENERATE = "inventory:putaway:generate";

    /**
     * View putaway tasks and their assignment status.
     */
    public static final String PUTAWAY_VIEW = "inventory:putaway:view";

    /**
     * Claim a putaway task for execution.
     */
    public static final String PUTAWAY_CLAIM = "inventory:putaway:claim";

    /**
     * Execute a putaway task and move inventory into storage.
     */
    public static final String PUTAWAY_EXECUTE = "inventory:putaway:execute";

    /**
     * Override location compatibility rules.
     * Required when a location is not valid for a SKU but business needs to
     * proceed.
     * Requires: mandatory reason code and free-text justification.
     */
    public static final String PUTAWAY_OVERRIDE_LOCATION_COMPATIBILITY =
            "inventory:putaway:override_location_compatibility";

    /**
     * Override location capacity limits.
     * Required when attempting to put away to a location at or near full capacity.
     * Overfill must be within configured tolerance (e.g., ≤ 5-10%).
     */
    public static final String PUTAWAY_OVERRIDE_LOCATION_CAPACITY = "inventory:putaway:override_location_capacity";

    // ==================== TRANSFER ORDER PERMISSIONS ====================

    /**
     * Create or cancel a cross-site transfer order (odoo-parity C1, issue #1035).
     * Cancellation is only allowed before dispatch.
     */
    public static final String TRANSFER_CREATE = "inventory:transfer:create";

    /**
     * View transfer orders, their lines, and lifecycle status.
     */
    public static final String TRANSFER_VIEW = "inventory:transfer:view";

    /**
     * Approve and dispatch a transfer order (posts TRANSFER_OUT at the source into transit).
     * Approval (config-flagged, decision D-8) rides on the dispatch authority.
     */
    public static final String TRANSFER_DISPATCH = "inventory:transfer:dispatch";

    /**
     * Receive dispatched transfer quantities at the destination (posts TRANSFER_IN).
     */
    public static final String TRANSFER_RECEIVE = "inventory:transfer:receive";

    /**
     * Short-close a dispatched transfer order with a loss/return disposition
     * (parity-C3; registered now so the catalog needs no second bump).
     */
    public static final String TRANSFER_SHORT_CLOSE = "inventory:transfer:short_close";

    // ==================== CYCLE COUNT PERMISSIONS ====================

    /**
     * Initiate a cycle count for reconciliation.
     * Required when source location shows zero on-hand but physical inventory
     * exists.
     */
    public static final String CYCLE_COUNT_INITIATE = "inventory:cycle_count:initiate";

    /**
     * View cycle count tasks and results.
     */
    public static final String CYCLE_COUNT_VIEW = "inventory:cycle_count:view";

    /**
     * Complete/submit cycle count results.
     */
    public static final String CYCLE_COUNT_COMPLETE = "inventory:cycle_count:complete";

    // ==================== REPLENISHMENT PERMISSIONS ====================

    /**
     * Manage replenishment: create/maintain replenishment policies and run the
     * batch replenishment scan (CAP-217 / odoo-parity F1, issue #1025).
     */
    public static final String REPLENISHMENT_MANAGE = "inventory:replenishment:manage";

    // ==================== LOT PERMISSIONS ====================

    /**
     * Manage lot lifecycle: quarantine/recall/release a lot and set its expiration/alert dates
     * (odoo-parity E3, issue #1047).
     */
    public static final String LOT_MANAGE = "inventory:lot:manage";

    // ==================== INVENTORY VIEW PERMISSIONS ====================

    /**
     * View on-hand inventory levels.
     */
    public static final String INVENTORY_VIEW = "inventory:on_hand:view";

    /**
     * Search inventory across locations.
     */
    public static final String INVENTORY_SEARCH = "inventory:on_hand:search";

    // ==================== VALUATION PERMISSIONS ====================

    /**
     * View inventory valuation (on-hand × current unit cost) at SKU level, including the as-of
     * variant (odoo-parity J2, issue #1052). Quantities × cost are doubly sensitive
     * (DECISION-INVENTORY-011), so valuation is gated separately from on-hand view and never
     * exposed under {@link #INVENTORY_VIEW} alone.
     */
    public static final String VALUATION_VIEW = "inventory:valuation:view";

    /**
     * Submit, approve, or reject a manual cost revaluation (standard-price / AVCO correction),
     * odoo-parity J4 (issue #1054). Distinct from the read-only {@link #VALUATION_VIEW}: revaluation
     * restates inventory value and posts a revaluation JE, so it gates all mutating revaluation
     * actions.
     */
    public static final String VALUATION_ADJUST = "inventory:valuation:adjust";

    // ==================== PERMISSION REGISTRATION ====================

    /**
     * Build inventory permission registration request for Security Domain
     */
    public static Map<String, Object> buildInventoryPermissionRegistration() {
        Map<String, Object> registration = new LinkedHashMap<>();

        registration.put("domain", "inventory");
        registration.put("serviceName", "pos-inventory");
        registration.put("version", "1.0");
        registration.put("permissions", buildPermissionDefinitions());

        return registration;
    }

    /**
     * Define all inventory permissions with metadata
     */
    private static List<Map<String, String>> buildPermissionDefinitions() {
        return Arrays.asList(
                // Adjustment permissions (3)
                permission(
                        ADJUSTMENT_CREATE,
                        "Create an adjustment request (draft/pending), capture reason code, quantity, and supporting notes",
                        "MEDIUM",
                        "Issue #37"),
                permission(
                        ADJUSTMENT_APPROVE,
                        "Approve and post an adjustment to the ledger (creates ADJUSTMENT_IN/OUT events)",
                        "HIGH",
                        "Issue #37"),
                permission(ADJUSTMENT_VIEW, "View adjustment history and details", "LOW"),

                // Stock movement permissions (1)
                permission(
                        STOCK_MOVEMENT_CREATE,
                        "Record RECEIVE, PUT_AWAY, PICK, ISSUE, RETURN, or TRANSFER movements directly in the inventory ledger",
                        "HIGH",
                        "Issue #37"),

                // Location permissions (2)
                permission(
                        LOCATION_VIEW,
                        "View storage location configuration and site default location assignments",
                        "LOW"),
                permission(
                        LOCATION_ADMIN,
                        "Manage storage location lifecycle and site default location assignments",
                        "HIGH"),

                // Pick list permissions (3)
                permission(PICK_LIST_CREATE, "Create pick lists for workorders", "MEDIUM"),
                permission(PICK_LIST_VIEW, "View pick lists and pick tasks", "LOW"),
                permission(
                        PICK_LIST_EXECUTE,
                        "Release pick lists, confirm pick tasks, update pick status, and complete picking workflows",
                        "HIGH"),

                // Putaway permissions (4)
                permission(PUTAWAY_GENERATE, "Generate putaway tasks from received inventory", "MEDIUM"),
                permission(PUTAWAY_VIEW, "View putaway tasks and their assignment status", "LOW"),
                permission(PUTAWAY_CLAIM, "Claim a putaway task for execution", "MEDIUM"),
                permission(PUTAWAY_EXECUTE, "Execute a putaway task and move inventory into storage", "HIGH"),

                // Putaway override permissions (2)
                permission(
                        PUTAWAY_OVERRIDE_LOCATION_COMPATIBILITY,
                        "Override location compatibility rules when location is not valid for SKU",
                        "HIGH",
                        "Clarification #229"),
                permission(
                        PUTAWAY_OVERRIDE_LOCATION_CAPACITY,
                        "Override location capacity limits when at or near full capacity",
                        "HIGH",
                        "Clarification #229"),

                // Cycle count permissions (3)
                permission(
                        CYCLE_COUNT_INITIATE,
                        "Initiate a cycle count for inventory reconciliation",
                        "MEDIUM",
                        "Clarification #229"),
                permission(CYCLE_COUNT_VIEW, "View cycle count tasks and results", "LOW"),
                permission(CYCLE_COUNT_COMPLETE, "Complete and submit cycle count results", "MEDIUM"),

                // Transfer order permissions (5)
                permission(
                        TRANSFER_CREATE,
                        "Create or cancel a cross-site transfer order (DRAFT; cancel only before dispatch)",
                        "MEDIUM",
                        "Issue #1035"),
                permission(TRANSFER_VIEW, "View transfer orders, their lines, and lifecycle status", "LOW"),
                permission(
                        TRANSFER_DISPATCH,
                        "Approve and dispatch a transfer order (posts TRANSFER_OUT at the source into transit)",
                        "HIGH",
                        "Issue #1035"),
                permission(
                        TRANSFER_RECEIVE,
                        "Receive dispatched transfer quantities at the destination (posts TRANSFER_IN)",
                        "HIGH",
                        "Issue #1036"),
                permission(
                        TRANSFER_SHORT_CLOSE,
                        "Short-close a dispatched transfer order with a loss/return disposition (parity-C3)",
                        "HIGH",
                        "Issue #1035"),

                // Replenishment permissions (1)
                permission(
                        REPLENISHMENT_MANAGE,
                        "Manage replenishment policies and run the batch replenishment scan",
                        "MEDIUM",
                        "Issue #1025"),

                // Lot permissions (1)
                permission(
                        LOT_MANAGE,
                        "Manage lot lifecycle: quarantine/recall/release and set expiration/alert dates",
                        "MEDIUM",
                        "Issue #1047"),

                // Inventory view permissions (2)
                permission(INVENTORY_VIEW, "View on-hand inventory levels at locations", "LOW"),
                permission(INVENTORY_SEARCH, "Search inventory across multiple locations", "LOW"),

                // Valuation permissions (2)
                permission(
                        VALUATION_VIEW,
                        "View inventory valuation (on-hand x current unit cost) at SKU level, including as-of",
                        "MEDIUM",
                        "Issue #1052"),
                permission(
                        VALUATION_ADJUST,
                        "Submit, approve, or reject a manual cost revaluation (standard-price / AVCO correction)",
                        "HIGH",
                        "Issue #1054"));
    }

    /**
     * Helper to create permission definition map
     */
    private static Map<String, String> permission(String name, String description, String riskLevel) {
        return permission(name, description, riskLevel, null);
    }

    /**
     * Helper to create permission definition map with reference
     */
    private static Map<String, String> permission(String name, String description, String riskLevel, String reference) {
        Map<String, String> perm = new LinkedHashMap<>();
        perm.put("name", name);
        perm.put("description", description);
        perm.put("riskLevel", riskLevel);
        if (reference != null) {
            perm.put("reference", reference);
        }
        return perm;
    }

    // ==================== PERMISSION GROUPS ====================

    /**
     * Adjustment workflow permissions
     */
    public static List<String> adjustmentPermissions() {
        return Arrays.asList(ADJUSTMENT_CREATE, ADJUSTMENT_APPROVE, ADJUSTMENT_VIEW);
    }

    /**
     * Stock movement permissions
     */
    public static List<String> stockMovementPermissions() {
        return Arrays.asList(STOCK_MOVEMENT_CREATE);
    }

    /**
     * Location permissions
     */
    public static List<String> locationPermissions() {
        return Arrays.asList(LOCATION_VIEW, LOCATION_ADMIN);
    }

    /**
     * Pick list permissions
     */
    public static List<String> pickListPermissions() {
        return Arrays.asList(PICK_LIST_CREATE, PICK_LIST_VIEW, PICK_LIST_EXECUTE);
    }

    /**
     * Putaway task permissions
     */
    public static List<String> putawayTaskPermissions() {
        return Arrays.asList(PUTAWAY_GENERATE, PUTAWAY_VIEW, PUTAWAY_CLAIM, PUTAWAY_EXECUTE);
    }

    /**
     * Putaway override permissions
     */
    public static List<String> putawayOverridePermissions() {
        return Arrays.asList(PUTAWAY_OVERRIDE_LOCATION_COMPATIBILITY, PUTAWAY_OVERRIDE_LOCATION_CAPACITY);
    }

    /**
     * Cycle count permissions
     */
    public static List<String> cycleCountPermissions() {
        return Arrays.asList(CYCLE_COUNT_INITIATE, CYCLE_COUNT_VIEW, CYCLE_COUNT_COMPLETE);
    }

    /**
     * Inventory viewing permissions
     */
    public static List<String> inventoryViewPermissions() {
        return Arrays.asList(INVENTORY_VIEW, INVENTORY_SEARCH);
    }

    /**
     * Valuation permissions
     */
    public static List<String> valuationPermissions() {
        return Arrays.asList(VALUATION_VIEW, VALUATION_ADJUST);
    }

    /**
     * Replenishment permissions
     */
    public static List<String> replenishmentPermissions() {
        return Arrays.asList(REPLENISHMENT_MANAGE);
    }

    /**
     * Lot management permissions
     */
    public static List<String> lotPermissions() {
        return Arrays.asList(LOT_MANAGE);
    }

    /**
     * Transfer order permissions
     */
    public static List<String> transferOrderPermissions() {
        return Arrays.asList(TRANSFER_CREATE, TRANSFER_VIEW, TRANSFER_DISPATCH, TRANSFER_RECEIVE, TRANSFER_SHORT_CLOSE);
    }

    // Receiving, purchase-order, scrap, shortage and ledger permissions, previously written as string literals at each
    // call site.
    public static final String ALLOCATIONS_REALLOCATE = "inventory:allocations:reallocate";
    public static final String ASN_CREATE = "inventory:asn:create";
    public static final String ASN_VIEW = "inventory:asn:view";
    public static final String GOODS_RECEIPT_CREATE = "inventory:goods_receipt:create";
    public static final String GOODS_RECEIPT_VIEW = "inventory:goods_receipt:view";
    public static final String ISSUE_PARTS = "inventory:issue:parts";
    public static final String LEDGER_VIEW = "inventory:ledger:view";
    public static final String LOCATION_SYNC = "inventory:location:sync";
    public static final String PURCHASE_ORDER_RECEIVE = "inventory:purchase_order:receive";
    public static final String RECEIVING_COMPLETE = "inventory:receiving:complete";
    public static final String RECEIVING_CREATE = "inventory:receiving:create";
    public static final String RECEIVING_VIEW = "inventory:receiving:view";
    public static final String RETURN_VIEW = "inventory:return:view";
    public static final String RETURN_WRITE = "inventory:return:write";
    public static final String SCRAP_APPROVE = "inventory:scrap:approve";
    public static final String SCRAP_CREATE = "inventory:scrap:create";
    public static final String SCRAP_VIEW = "inventory:scrap:view";
    public static final String SHORTAGE_RESOLVE = "inventory:shortage:resolve";
    public static final String SHORTAGE_VIEW = "inventory:shortage:view";
    public static final String SUPPLIER_STOCK_HINT_VIEW = "inventory:supplier_stock_hint:view";
}
