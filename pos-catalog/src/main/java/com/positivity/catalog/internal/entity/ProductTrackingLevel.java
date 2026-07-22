package com.positivity.catalog.internal.entity;

/**
 * Stock tracking level of a product (#1023, inventory Odoo-parity Story X1).
 *
 * <p>Owned by pos-catalog and replicated to pos-inventory via {@code catalog.product.updated}
 * events; inventory enforces lot/serial capture on receipts and issues based on this flag.
 */
public enum ProductTrackingLevel {
    /** No lot or serial tracking (default). */
    NONE,
    /** Stock is tracked per lot/batch number. */
    LOT,
    /** Stock is tracked per individual serial number. */
    SERIAL
}
