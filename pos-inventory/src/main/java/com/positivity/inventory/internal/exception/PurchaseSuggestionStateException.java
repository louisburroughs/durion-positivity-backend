package com.positivity.inventory.internal.exception;

import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import java.util.UUID;

/**
 * An accept/dismiss lifecycle transition was attempted from an incompatible purchase
 * suggestion status (odoo-parity F4, issue #1044) — a deterministic 409 conflict.
 */
public class PurchaseSuggestionStateException extends RuntimeException {

    public static final String ERROR_CODE = "PURCHASE_SUGGESTION_INVALID_STATE";

    public PurchaseSuggestionStateException(UUID suggestionId, PurchaseSuggestionStatus current, String attempted) {
        super("Suggestion " + suggestionId + " is " + current + " and cannot be " + attempted);
    }
}
