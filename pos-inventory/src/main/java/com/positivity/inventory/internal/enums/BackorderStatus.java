package com.positivity.inventory.internal.enums;

/**
 * Lifecycle of a {@code BackorderRecord} (odoo-parity G1, issue #1046).
 *
 * <p>{@code OPEN → RESOLVED} when availability covers the shortage (auto-resolution, oldest first),
 * or {@code OPEN → CANCELLED} when the demand is withdrawn (line cancelled). A CANCELLED backorder
 * is terminal and never auto-resolves.
 */
public enum BackorderStatus {
    OPEN,
    RESOLVED,
    CANCELLED
}
