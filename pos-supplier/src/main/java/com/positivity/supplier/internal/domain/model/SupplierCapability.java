package com.positivity.supplier.internal.domain.model;

/**
 * The business capabilities a vendor profile may bind (ADR-0050 §3). A capability with no
 * binding for a vendor is disabled for that deployment and surfaces as a typed
 * {@code CAPABILITY_NOT_CONFIGURED} outcome, never an error leak.
 *
 * <p>Registry resolution is keyed by {@code (capability, protocolFamily, version)}
 * (ADR-0051 §3).
 */
public enum SupplierCapability {
    ORDER_CREATE,
    ORDER_STATUS,
    STOCK_INQUIRY,
    STOCK_REPORT,
    PRICE_CATALOG,
    INVOICE_FETCH,
    SHIPMENT_TRACKING,
    WORKORDER_AUTHORIZATION,
    MARKETING_CATALOG,
    TIRE_IDENTIFICATION
}
