package com.positivity.inventory.model;

/**
 * Defines inventory ledger event types for tracking stock movements.
 * 
 * <p>
 * These event types are used to compute On-Hand and Available-to-Promise (ATP)
 * quantities
 * as defined in ADR-0001 (Inventory Ledger ATP Computation).
 * 
 * <p>
 * <strong>On-Hand Calculation:</strong> Net sum of physical stock movement
 * events.
 * On-Hand events include: INBOUND (positive) and OUTBOUND (negative) event
 * types.
 * 
 * <p>
 * <strong>ATP Calculation:</strong> ATP = On-Hand - Allocations.
 * Reservation and allocation events affect availability but NOT physical
 * on-hand.
 * 
 * @see <a href=
 *      "/docs/adr/0001-inventory-ledger-atp-computation.md">ADR-0001</a>
 */
public enum InventoryLedgerEventType {

    // ============================================================================
    // INBOUND EVENTS - Add to On-Hand (Positive)
    // ============================================================================

    /**
     * Receiving inventory into stock from a purchase order or supplier.
     * Direction: INBOUND (+)
     * Affects On-Hand: YES
     */
    GOODS_RECEIPT(EventDirection.INBOUND, true),

    /**
     * Inter-location transfer received from another location.
     * Direction: INBOUND (+)
     * Affects On-Hand: YES
     */
    TRANSFER_IN(EventDirection.INBOUND, true),

    /**
     * Customer return accepted back into inventory.
     * Direction: INBOUND (+)
     * Affects On-Hand: YES
     */
    RETURN_TO_STOCK(EventDirection.INBOUND, true),

    /**
     * Manual positive inventory adjustment (e.g., found inventory, correction).
     * Direction: INBOUND (+)
     * Affects On-Hand: YES
     */
    ADJUSTMENT_IN(EventDirection.INBOUND, true),

    /**
     * Cycle count revealed more inventory than recorded in system.
     * Direction: INBOUND (+)
     * Affects On-Hand: YES
     */
    COUNT_VARIANCE_IN(EventDirection.INBOUND, true),

    // ============================================================================
    // OUTBOUND EVENTS - Subtract from On-Hand (Negative)
    // ============================================================================

    /**
     * Inventory issued or consumed to workorder, production, or job.
     * Direction: OUTBOUND (-)
     * Affects On-Hand: YES
     */
    GOODS_ISSUE(EventDirection.OUTBOUND, true),

    /**
     * Inter-location transfer shipped to another location.
     * Direction: OUTBOUND (-)
     * Affects On-Hand: YES
     */
    TRANSFER_OUT(EventDirection.OUTBOUND, true),

    /**
     * Inventory written off due to damage, shrinkage, obsolescence.
     * Direction: OUTBOUND (-)
     * Affects On-Hand: YES
     */
    SCRAP_OUT(EventDirection.OUTBOUND, true),

    /**
     * Manual negative inventory adjustment (e.g., damaged, lost, correction).
     * Direction: OUTBOUND (-)
     * Affects On-Hand: YES
     */
    ADJUSTMENT_OUT(EventDirection.OUTBOUND, true),

    /**
     * Cycle count revealed less inventory than recorded in system.
     * Direction: OUTBOUND (-)
     * Affects On-Hand: YES
     */
    COUNT_VARIANCE_OUT(EventDirection.OUTBOUND, true),

    /**
     * Cycle count adjustment posted after approval.
     * Direction: Can be INBOUND (+) or OUTBOUND (-) depending on variance.
     * Affects On-Hand: YES
     */
    ADJUST_CYCLE_COUNT(EventDirection.NEUTRAL, true),

    // ============================================================================
    // ALLOCATION/RESERVATION EVENTS - Affect ATP but NOT On-Hand
    // ============================================================================

    /**
     * Soft allocation created for sales order (reservation).
     * Direction: NEUTRAL
     * Affects On-Hand: NO (affects ATP only)
     */
    RESERVATION_CREATED(EventDirection.NEUTRAL, false),

    /**
     * Soft allocation cancelled or expired.
     * Direction: NEUTRAL
     * Affects On-Hand: NO (affects ATP only)
     */
    RESERVATION_RELEASED(EventDirection.NEUTRAL, false),

    /**
     * Hard allocation created for picking/packing.
     * Direction: NEUTRAL
     * Affects On-Hand: NO (affects ATP only)
     */
    ALLOCATION_CREATED(EventDirection.NEUTRAL, false),

    /**
     * Hard allocation cancelled or released.
     * Direction: NEUTRAL
     * Affects On-Hand: NO (affects ATP only)
     */
    ALLOCATION_RELEASED(EventDirection.NEUTRAL, false),

    /**
     * Backorder created for unfulfilled demand.
     * Direction: NEUTRAL
     * Affects On-Hand: NO (tracking only)
     */
    BACKORDER_CREATED(EventDirection.NEUTRAL, false),

    /**
     * Backorder fulfilled or cancelled.
     * Direction: NEUTRAL
     * Affects On-Hand: NO (tracking only)
     */
    BACKORDER_RESOLVED(EventDirection.NEUTRAL, false),

    /**
     * Pick task (work instruction) created.
     * Direction: NEUTRAL
     * Affects On-Hand: NO (unless it posts GOODS_ISSUE or TRANSFER_OUT)
     */
    PICK_TASK_CREATED(EventDirection.NEUTRAL, false),

    /**
     * Pick task completed.
     * Direction: NEUTRAL
     * Affects On-Hand: NO (unless it posts GOODS_ISSUE or TRANSFER_OUT)
     */
    PICK_TASK_COMPLETED(EventDirection.NEUTRAL, false);

    private final EventDirection direction;
    private final boolean affectsOnHand;

    InventoryLedgerEventType(EventDirection direction, boolean affectsOnHand) {
        this.direction = direction;
        this.affectsOnHand = affectsOnHand;
    }

    /**
     * Returns the direction of this event type (INBOUND, OUTBOUND, or NEUTRAL).
     * 
     * @return the event direction
     */
    public EventDirection getDirection() {
        return direction;
    }

    /**
     * Indicates whether this event type affects the physical on-hand quantity.
     * 
     * @return true if this event affects on-hand, false otherwise
     */
    public boolean affectsOnHand() {
        return affectsOnHand;
    }

    /**
     * Returns the sign multiplier for quantity calculations.
     * INBOUND = +1, OUTBOUND = -1, NEUTRAL = 0
     * 
     * @return the sign multiplier
     */
    public int getSignMultiplier() {
        return direction.getSignMultiplier();
    }

    /**
     * Enum representing the direction of an inventory event.
     */
    public enum EventDirection {
        /** Inventory coming into location (adds to on-hand) */
        INBOUND(1),

        /** Inventory leaving location (subtracts from on-hand) */
        OUTBOUND(-1),

        /** Event does not affect physical inventory (e.g., reservation) */
        NEUTRAL(0);

        private final int signMultiplier;

        EventDirection(int signMultiplier) {
            this.signMultiplier = signMultiplier;
        }

        /**
         * Returns the sign multiplier for quantity calculations.
         * 
         * @return 1 for INBOUND, -1 for OUTBOUND, 0 for NEUTRAL
         */
        public int getSignMultiplier() {
            return signMultiplier;
        }
    }
}
