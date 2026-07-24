package com.positivity.order.internal.entity;

/**
 * How a return's refund is issued (odoo-parity story F1, issue #1086, spec R5.1).
 * {@code ORIGINAL_TENDER} reverses the original payment(s); {@code STORE_CREDIT} issues store
 * credit (requires a validated customer, story I2); {@code ON_ACCOUNT_CREDIT} credits the
 * customer's AR account.
 */
public enum RefundMethod {
    ORIGINAL_TENDER,
    STORE_CREDIT,
    ON_ACCOUNT_CREDIT
}
