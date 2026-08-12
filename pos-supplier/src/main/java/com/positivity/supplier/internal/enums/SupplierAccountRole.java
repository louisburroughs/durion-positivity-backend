package com.positivity.supplier.internal.enums;

/**
 * Persistence mirror of {@code service.model.SupplierAccountRole} (ADR-0050 §5): the
 * canonical commercial-account role.
 */
public enum SupplierAccountRole {
    /** Invoicing/settlement account, one per profile at legal-entity level. */
    BILLING,
    /** Receiving-location account, mapped per pos-location UUID. */
    DELIVERY
}
