package com.positivity.inventory.internal.enums;

/**
 * Origin label for a scrap document's unit-cost snapshot (odoo-parity D1, issue #1030).
 *
 * <p>Interim cost rule (ADR-0048 / plan D-6): the snapshot is the SKU's latest receipt cost —
 * the most recent {@code GOODS_RECEIPT} ledger entry's {@code unitCost}, falling back to the
 * latest non-null {@code unitCost} of any ledger entry for the SKU. The label is stored on the
 * record (and carried on {@code ScrapPostedV1}) so downstream consumers can tell a derived
 * interim cost from a missing one. odoo-parity J1 replaces this with the authoritative cost
 * source and will extend this enum.
 */
public enum ScrapCostSource {

    /** Snapshot derived from the SKU's most recent costed ledger entry (receipt-first). */
    LATEST_RECEIPT,

    /** No cost could be derived; the snapshot is {@code null} and the value is unknown. */
    NONE
}
