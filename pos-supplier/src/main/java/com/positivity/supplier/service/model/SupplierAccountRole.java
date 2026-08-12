package com.positivity.supplier.service.model;

/**
 * Canonical commercial-account roles of a vendor profile (ADR-0050 §5). Vendor vocabulary
 * ({@code billTo}/{@code shipTo}, {@code BuyerParty}/{@code Consignee}) exists only inside
 * adapters — canonical code, DTOs, events, and UI use billing/delivery.
 */
public enum SupplierAccountRole {
    /** Invoicing/settlement account, typically one per profile at legal-entity level. */
    BILLING,
    /** Receiving-location account, mapped per pos-location UUID. */
    DELIVERY
}
