package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Thrown when a transfer order id does not resolve to a document (odoo-parity C1, issue #1035).
 * Maps to 404 {@code NOT_FOUND}.
 */
public class TransferOrderNotFoundException extends RuntimeException {

    public TransferOrderNotFoundException(UUID transferOrderId) {
        super("Transfer order not found: " + transferOrderId);
    }
}
