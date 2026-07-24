package com.positivity.inventory.internal.enums;

/**
 * What drove an open backorder to RESOLVED (odoo-parity G1, issue #1046). Set only on resolve.
 *
 * <ul>
 *   <li>{@code AVAILABILITY} — an inbound on-hand posting raised availability for the SKU/site and
 *       the auto-resolver covered the shortage. This is the source stamped by the availability-driven
 *       trigger (the only automated resolution path G1 wires).</li>
 *   <li>{@code REPLENISHMENT_RECEIPT} — reserved for a future path that attributes resolution to a
 *       specific replenishment receipt document.</li>
 *   <li>{@code MANUAL} — reserved for an operator-driven resolution (G2 shortage resolution).</li>
 * </ul>
 */
public enum BackorderResolutionSource {
    AVAILABILITY,
    REPLENISHMENT_RECEIPT,
    MANUAL
}
