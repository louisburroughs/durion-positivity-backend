package com.positivity.inventory.internal.enums;

/**
 * Disposition of the undelivered in-transit remainder when a transfer order is short-closed
 * (odoo-parity C3, issue #1039; spec C4).
 *
 * <p>One disposition applies to the whole order (v1 simplification — per-line dispositions can
 * be layered on later without schema changes to the ledger). Either way every remaining unit
 * leaves transit atomically; the two dispositions differ only in where the stock ends up.
 */
public enum TransferShortCloseDisposition {

    /**
     * The remainder never arrived and is written off: a constructive {@code TRANSFER_IN} at the
     * destination clears the in-transit balance and a paired {@code SCRAP_OUT}
     * ({@code reasonCode = LOST}) removes it from on-hand — global on-hand drops by the
     * remainder.
     */
    LOST_IN_TRANSIT,

    /**
     * The remainder came back to the source site: a constructive receive at the destination,
     * an immediate {@code TRANSFER_OUT} back, and a {@code TRANSFER_IN} at the source restore
     * source on-hand — global on-hand is unchanged.
     */
    RETURNED_TO_SOURCE
}
