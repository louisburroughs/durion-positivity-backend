package com.positivity.inventory.internal.enums;

/**
 * Lifecycle status of an {@link com.positivity.inventory.internal.entity.InventorySerialUnit}
 * (odoo-parity E4, issue #1050; spec §6 E4).
 *
 * <p>A serial unit is enumerated {@code IN_STOCK} by an inbound receipt posting and leaves stock
 * when a matching outbound posting names it: {@code ISSUED} on consumption/issue to a workorder,
 * {@code SCRAPPED} on a write-off, {@code RETURNED} when a customer/warranty return re-lands it in
 * quarantine. Only {@code IN_STOCK} units count toward serial-derived on-hand.
 */
public enum InventorySerialStatus {
    IN_STOCK,
    ISSUED,
    RETURNED,
    SCRAPPED
}
