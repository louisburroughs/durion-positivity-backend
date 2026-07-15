package com.positivity.warranty.internal.enums;

/** Provenance of a claim line (SalesOrderLine sourceType pattern, PRD §3.5). */
public enum LineSourceType {
    INVOICE_LINE,
    WORKORDER_PART,
    WORKORDER_SERVICE,
    MANUAL
}
