package com.positivity.inventory.internal.enums;

/**
 * Preferred sourcing channel for replenishing a policy's pick face (odoo-parity F3, issue
 * #1041; spec F3).
 *
 * <p><b>Stored only in F3</b> — no evaluation path reads it yet. Parity-F5 (internal
 * sourcing) consumes it: {@code INTERNAL_TRANSFER} restricts sourcing to backstock/transfer
 * moves, {@code PURCHASE} routes straight to a purchase suggestion (F4), and {@code EITHER}
 * lets the sourcing engine decide (internal surplus first, purchase as fallback).
 */
public enum ReplenishmentSourceType {
    INTERNAL_TRANSFER,
    PURCHASE,
    EITHER
}
