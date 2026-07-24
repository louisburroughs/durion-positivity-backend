package com.positivity.order.internal.entity;

/**
 * Lifecycle of a register session (odoo-parity story G1, spec R6.1). {@code OPEN} accepts orders and
 * cash movements; {@code CLOSING} is entered by begin-close (counted cash recorded, no new activity);
 * {@code CLOSED} is terminal after confirm-close reconciles the drawer.
 */
public enum RegisterSessionStatus {
    OPEN,
    CLOSING,
    CLOSED
}
