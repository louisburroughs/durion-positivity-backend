package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Thrown when posting an approved scrap document to the inventory ledger fails for an
 * unexpected reason (odoo-parity D1, issue #1030). Mirrors
 * {@link AdjustmentLedgerPostingException} for cycle-count adjustments.
 */
public class ScrapLedgerPostingException extends RuntimeException {

    public ScrapLedgerPostingException(UUID scrapId, String message, Throwable cause) {
        super("Scrap " + scrapId + ": " + message, cause);
    }
}
