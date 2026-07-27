package com.positivity.inventory.internal.enums;

/**
 * Destination-selection strategy for a {@code PutawayRule} (odoo-parity K2,
 * issue #1055; Odoo's putaway rule {@code storage_category}/sublocation
 * strategy analog).
 *
 * <p>The strategy decides which sublocation/bin a matched putaway rule
 * suggests for a received line:
 * <ul>
 *   <li>{@link #FIXED} — the rule's configured {@code destinationLocationId},
 *       unchanged pre-K2 behavior and the default.
 *   <li>{@link #LAST_USED} — the sublocation most recently used for a
 *       successful putaway of the same SKU, falling back to the rule's fixed
 *       destination when the SKU has never been put away.
 *   <li>{@link #CLOSEST_AVAILABLE} — the capacity-aware nearest bin, ranked by
 *       odoo-parity H1 topology proximity to the rule's fixed destination and
 *       skipping bins at or over capacity, falling back to the fixed
 *       destination when no candidate has room.
 * </ul>
 */
public enum PutawayDestinationStrategy {

    /** The rule's configured fixed destination (default, pre-K2 behavior). */
    FIXED,

    /** Most recently used sublocation for the SKU, else the fixed destination. */
    LAST_USED,

    /** Capacity-aware nearest bin via H1 proximity, else the fixed destination. */
    CLOSEST_AVAILABLE
}
