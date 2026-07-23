package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Thrown when a scrap document does not exist (odoo-parity D1, issue #1030).
 */
public class ScrapNotFoundException extends RuntimeException {

    public ScrapNotFoundException(UUID scrapId) {
        super("Scrap not found: " + scrapId);
    }
}
