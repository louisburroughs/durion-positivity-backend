package com.positivity.order.internal.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A vendor's status as the purchase-order screens read it from pos-order's vendor copy (CAP:550, #2680). The
 * API-side twin of {@code ExtSupplierVendor.Status}: controllers and DTOs do not depend on entities (ArchUnit), and
 * the copy only ever holds these two values (pos-supplier publishes no other).
 */
@Schema(description = "Vendor status as pos-supplier publishes it: only an ACTIVE vendor takes a new purchase order")
public enum PurchaseOrderVendorStatus {
    ACTIVE,
    INACTIVE
}
