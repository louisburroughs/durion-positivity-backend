package com.positivity.inventory.internal.security;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public static final String PUTAWAY_OVERRIDE_LOCATION_COMPATIBILITY = "inventory:putaway:override_location_compatibility";

    /**
     * Override location capacity limits.
     * Required when attempting to put away to a location at or near full capacity.
     * Overfill must be within configured tolerance (e.g., ≤ 5-10%).
     */
    public static final String PUTAWAY_OVERRIDE_LOCATION_CAPACITY = "inventory:putaway:override_location_capacity";

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

    // ==================== INVENTORY VIEW PERMISSIONS ====================

    /**
     * View on-hand inventory levels.
     */
    public static final String INVENTORY_VIEW = "inventory:on_hand:view";

    /**
     * Search inventory across locations.
     */
    public static final String INVENTORY_SEARCH = "inventory:on_hand:search";

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
                permission(ADJUSTMENT_CREATE,
                        "Create an adjustment request (draft/pending), capture reason code, quantity, and supporting notes",
                        "MEDIUM", "Issue #37"),
                permission(ADJUSTMENT_APPROVE,
                        "Approve and post an adjustment to the ledger (creates ADJUSTMENT_IN/OUT events)",
                        "HIGH", "Issue #37"),
                permission(ADJUSTMENT_VIEW,
                        "View adjustment history and details",
                        "LOW"),

                // Stock movement permissions (1)
                permission(STOCK_MOVEMENT_CREATE,
                        "Record RECEIVE, PUT_AWAY, PICK, ISSUE, RETURN, or TRANSFER movements directly in the inventory ledger",
                        "HIGH", "Issue #37"),

                // Location permissions (2)
                permission(LOCATION_VIEW,
                        "View storage location configuration and site default location assignments",
                        "LOW"),
                permission(LOCATION_ADMIN,
                        "Manage storage location lifecycle and site default location assignments",
                        "HIGH"),

                // Pick list permissions (3)
                permission(PICK_LIST_CREATE,
                        "Create pick lists for workorders",
                        "MEDIUM"),
                permission(PICK_LIST_VIEW,
                        "View pick lists and pick tasks",
                        "LOW"),
                permission(PICK_LIST_EXECUTE,
                        "Release pick lists, confirm pick tasks, update pick status, and complete picking workflows",
                        "HIGH"),

                // Putaway permissions (4)
                permission(PUTAWAY_GENERATE,
                        "Generate putaway tasks from received inventory",
                        "MEDIUM"),
                permission(PUTAWAY_VIEW,
                        "View putaway tasks and their assignment status",
                        "LOW"),
                permission(PUTAWAY_CLAIM,
                        "Claim a putaway task for execution",
                        "MEDIUM"),
                permission(PUTAWAY_EXECUTE,
                        "Execute a putaway task and move inventory into storage",
                        "HIGH"),

                // Putaway override permissions (2)
                permission(PUTAWAY_OVERRIDE_LOCATION_COMPATIBILITY,
                        "Override location compatibility rules when location is not valid for SKU",
                        "HIGH", "Clarification #229"),
                permission(PUTAWAY_OVERRIDE_LOCATION_CAPACITY,
                        "Override location capacity limits when at or near full capacity",
                        "HIGH", "Clarification #229"),

                // Cycle count permissions (3)
                permission(CYCLE_COUNT_INITIATE,
                        "Initiate a cycle count for inventory reconciliation",
                        "MEDIUM", "Clarification #229"),
                permission(CYCLE_COUNT_VIEW,
                        "View cycle count tasks and results",
                        "LOW"),
                permission(CYCLE_COUNT_COMPLETE,
                        "Complete and submit cycle count results",
                        "MEDIUM"),

                // Inventory view permissions (2)
                permission(INVENTORY_VIEW,
                        "View on-hand inventory levels at locations",
                        "LOW"),
                permission(INVENTORY_SEARCH,
                        "Search inventory across multiple locations",
                        "LOW"));
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
}
