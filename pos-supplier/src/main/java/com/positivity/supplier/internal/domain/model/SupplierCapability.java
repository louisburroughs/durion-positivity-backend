package com.positivity.supplier.internal.domain.model;

/**
 * The business capabilities a vendor profile may bind (ADR-0050 §3). A capability with no
 * binding for a vendor is disabled for that deployment and surfaces as a typed
 * {@code CAPABILITY_NOT_CONFIGURED} outcome, never an error leak.
 *
 * <p>Registry resolution is keyed by {@code (capability, protocolFamily, version)}
 * (ADR-0051 §3).
 *
 * <p>There is deliberately no shipment-tracking capability (#1313). EDIWheel shipment
 * tracking is an exchange between logistics providers and suppliers, not one a service
 * provider participates in, so the LEX v1 document we hold offers only the write operation
 * a carrier would call — nothing to read. Should a non-EDIWheel source ever offer shipment
 * milestones (a carrier API, a Michelin S2S operation), that is a new capability with its
 * own spec, not a revival of this one.
 */
public enum SupplierCapability {
    ORDER_CREATE,
    ORDER_STATUS,
    STOCK_INQUIRY,
    STOCK_REPORT,
    PRICE_CATALOG,
    INVOICE_FETCH,
    WORKORDER_AUTHORIZATION,
    MARKETING_CATALOG,
    TIRE_IDENTIFICATION
}
