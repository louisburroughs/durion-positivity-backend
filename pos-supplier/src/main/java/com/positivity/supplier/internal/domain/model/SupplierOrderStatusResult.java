package com.positivity.supplier.internal.domain.model;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Vendor answer to an order-status query by wire document id. This is the reconciliation
 * primitive of ADR-0052 §3: after an ambiguous order-create outcome the orchestrator asks the
 * vendor whether it knows the document — {@link Status#NOT_FOUND} versus a known state decides
 * between safe re-dispatch and {@code MANUAL_REVIEW}.
 *
 * @param documentId the wire document id that was queried; never blank
 * @param status vendor-known state of the order
 * @param supplierOrderNumber vendor-native order reference, when the vendor knows the order
 * @param vendorReason vendor-supplied detail (e.g. rejection reason), verbatim
 */
public record SupplierOrderStatusResult(
        @NonNull String documentId,
        @NonNull Status status,
        @Nullable String supplierOrderNumber,
        @Nullable String vendorReason) {

    public enum Status {
        /** The vendor has no order for this document id. */
        NOT_FOUND,
        /** The vendor knows the order and is still processing it. */
        IN_PROGRESS,
        /** The vendor accepted the order. */
        CONFIRMED,
        /** The vendor rejected the order. */
        REJECTED
    }

    public SupplierOrderStatusResult {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
    }
}
