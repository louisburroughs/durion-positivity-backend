package com.positivity.inventory.internal.enums;

/**
 * Lifecycle of a purchase suggestion (odoo-parity F4, issue #1044; spec F4, plan D-3).
 *
 * <p>{@code SUGGESTED → ACCEPTED → CONVERTED | DISMISSED}. Per decision D-3 (OQ-3 ACCEPTED)
 * the ACCEPT transition is <b>human-mandatory</b>: the replenishment scan only ever creates
 * {@code SUGGESTED} rows and never accepts or converts them — two human gates guard spend
 * (accept here, then the existing PO approval workflow on the converted DRAFT PO).
 */
public enum PurchaseSuggestionStatus {

    /** Created by the replenishment scan (or event path); awaiting a human decision. */
    SUGGESTED,

    /** A human accepted the suggestion; eligible for conversion into a DRAFT PO. */
    ACCEPTED,

    /** Converted into a DRAFT purchase order ({@code convertedPurchaseOrderId} set). Terminal. */
    CONVERTED,

    /** A human dismissed the suggestion with a mandatory reason. Terminal. */
    DISMISSED
}
