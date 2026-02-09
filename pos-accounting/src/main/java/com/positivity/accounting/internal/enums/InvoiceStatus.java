package com.positivity.accounting.internal.enums;

/**
 * Invoice lifecycle states used across accounting integrations and DTOs.
 */
public enum InvoiceStatus {
    OPEN,
    PARTIALLY_PAID,
    PAID_IN_FULL,
    VOIDED,
    CANCELLED
}
