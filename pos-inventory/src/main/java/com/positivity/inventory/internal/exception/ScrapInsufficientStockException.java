package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Thrown when posting a scrap's {@code SCRAP_OUT} entry would take on-hand below zero and no
 * authorized negative-stock override was supplied (odoo-parity D1, issue #1030).
 *
 * <p>Surfaced as a deterministic 422 ({@link #ERROR_CODE}) with a guided-reconciliation message
 * mirroring the putaway source-on-hand rule ({@code docs/putaway-validation-rules.md}): the
 * caller must reconcile inventory (cycle count or adjustment) before scrapping, or re-submit
 * with an explicit override under {@code inventory:adjustment:override}.
 */
public class ScrapInsufficientStockException extends RuntimeException {

    /** Deterministic error code for the ApiError envelope. */
    public static final String ERROR_CODE = "SCRAP_INSUFFICIENT_STOCK";

    public ScrapInsufficientStockException(String stockItemId, UUID locationId, int quantity) {
        super("Insufficient on-hand to scrap " + quantity + " of stock item " + stockItemId + " at location "
                + locationId + ". Reconcile inventory first: initiate a cycle count"
                + " (inventory:cycle_count:initiate) or an inventory adjustment (inventory:adjustment:create)"
                + " for the location, or re-submit with negativeStockOverride=true if you hold"
                + " inventory:adjustment:override.");
    }
}
