package com.positivity.inventory.internal.enums;

/**
 * Reason taxonomy for scrap/write-off documents (odoo-parity D1, issue #1030).
 *
 * <p>Extensible list mirroring Odoo's {@code stock.scrap} reason tags; the value is copied
 * verbatim into the {@code SCRAP_OUT} ledger entry's {@code reasonCode} column so reason
 * analytics stay queryable from ledger and document alike. {@link #OTHER} requires free-text
 * notes on the scrap record.
 */
public enum ScrapReasonCode {

    /** Physically damaged and unusable. */
    DAMAGED,

    /** Past expiry/shelf-life date. */
    EXPIRED,

    /** Missing stock written off as lost. */
    LOST,

    /** Removed under a manufacturer or regulatory recall. */
    RECALLED,

    /** Contaminated and unsafe to sell or use. */
    CONTAMINATED,

    /** Destroyed as part of a warranty claim settlement. */
    WARRANTY_DESTROYED,

    /** Any other reason — free-text notes are mandatory. */
    OTHER
}
