package com.positivity.supplier.service.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Transmission state of one purchase-order transmission intent (ADR-0049 §2 — pos-supplier
 * holds transmission state only; the purchase-order aggregate stays in pos-order).
 *
 * <p>Placeholder-level for the CAP-317 foundation slice; grows with CAP-320. Vendor-native
 * references ({@code supplierOrderNumber}) are attributes, never keys (ADR-0013/0027).
 *
 * @param transmissionIntentId immutable idempotency identity of the transmission intent
 *     (ADR-0052 §1)
 * @param purchaseOrderId originating pos-order purchase-order aggregate id
 * @param documentId wire document id derived from {@code transmissionIntentId}; never blank
 * @param state current transmission state
 * @param supplierOrderNumber vendor-native order reference, when the vendor has assigned one
 * @param vendorReason vendor-supplied detail (e.g. rejection reason), verbatim
 * @param lastTransitionAt when the state last changed, when known
 */
public record OrderTransmissionStatus(
        @NonNull UUID transmissionIntentId,
        @NonNull UUID purchaseOrderId,
        @NonNull String documentId,
        @NonNull State state,
        @Nullable String supplierOrderNumber,
        @Nullable String vendorReason,
        @Nullable Instant lastTransitionAt) {

    /** Transmission states; the ambiguity taxonomy hardens with CAP-320 (ADR-0052). */
    public enum State {
        /** The intent exists but has not been dispatched to the vendor yet. */
        PENDING,
        /** The order was sent; no vendor decision yet. */
        DISPATCHED,
        /** The vendor accepted the order. */
        CONFIRMED,
        /** The vendor rejected the order. */
        REJECTED,
        /** A post-send ambiguous outcome awaits operator reconciliation (ADR-0052 §3). */
        MANUAL_REVIEW
    }

    public OrderTransmissionStatus {
        Objects.requireNonNull(transmissionIntentId, "transmissionIntentId must not be null");
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        if (documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
    }
}
