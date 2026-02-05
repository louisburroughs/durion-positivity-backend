package com.positivity.invoice.internal.enums;

/**
 * Invoice grouping strategies for billing rules.
 * CAP:092 - Preferences & Billing Rules
 */
public enum InvoiceGroupingStrategy {
    PER_WORKORDER,
    PER_VEHICLE,
    SINGLE_INVOICE
}
