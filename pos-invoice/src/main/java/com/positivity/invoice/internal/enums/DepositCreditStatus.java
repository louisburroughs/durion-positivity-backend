package com.positivity.invoice.internal.enums;

/**
 * Lifecycle of a deposit credit (odoo-parity story E4, issue #1085, spec R7.4). A credit starts
 * {@code AVAILABLE} with its full original amount, moves to {@code PARTIALLY_APPLIED} as it is drawn
 * down against settlement invoices, reaches {@code FULLY_APPLIED} when its remaining balance hits
 * zero, or {@code REFUNDED} when the source is cancelled and the deposit is returned.
 */
public enum DepositCreditStatus {
    AVAILABLE,
    PARTIALLY_APPLIED,
    FULLY_APPLIED,
    REFUNDED
}
