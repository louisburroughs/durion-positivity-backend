package com.positivity.invoice.internal.exception;

import java.util.UUID;

/**
 * A deposit credit was looked up by an id that does not exist (odoo-parity story E4, #1085). Maps to
 * a 404 {@code DEPOSIT_CREDIT_NOT_FOUND} ApiError.
 */
public class DepositCreditNotFoundException extends RuntimeException {

    public DepositCreditNotFoundException(UUID depositCreditId) {
        super("Deposit credit not found: " + depositCreditId);
    }
}
