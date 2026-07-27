package com.positivity.inventory.internal.enums;

/**
 * Lifecycle status of an {@link com.positivity.inventory.internal.entity.InventoryLot}
 * (odoo-parity E1, issue #1038; spec §6).
 *
 * <p>E1 only creates lots as {@code ACTIVE}; the blocking semantics of
 * {@code QUARANTINED}/{@code RECALLED} (suggestion + reservation-fill exclusion) and the
 * {@code CONSUMED} transition arrive with E2/E3.
 */
public enum InventoryLotStatus {
    ACTIVE,
    QUARANTINED,
    RECALLED,
    CONSUMED
}
