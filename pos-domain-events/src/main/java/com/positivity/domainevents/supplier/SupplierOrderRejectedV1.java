package com.positivity.domainevents.supplier;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code supplier.order.rejected} v1 on {@code supplier.events.v1}
 * (ADR-0049 §3, CAP-320 #1226).
 *
 * <p>The transmission ended without the vendor holding the order. Three different things end
 * that way and the ordering domain needs to tell them apart, which is what {@link Reason} is
 * for: a vendor refusal, an operator establishing the vendor never received it, and an operator
 * abandoning the transmission are the same outcome for the purchase order and very different
 * events on its timeline.
 *
 * <h2>Rejected is terminal for the intent, not for the order</h2>
 *
 * The intent is finished and will never be re-dispatched — that is the ADR-0052 §4 guarantee
 * that keeps a "not received" from becoming a duplicate delivery. Ordering the goods again is a
 * new transmission intent with a new document id, requested by a fresh
 * {@link SupplierOrderRequestedV1} with {@code intentType = REPLACEMENT}. Nothing in
 * pos-supplier re-sends on its own.
 *
 * @param transmissionIntentId the transmission intent that ended; the event aggregate id
 * @param purchaseOrderId originating purchase-order aggregate id
 * @param vendorProfileId vendor profile the order was sent to (ADR-0050 §1 identity)
 * @param supplierRef profile alias at rejection time; descriptive only
 * @param documentId our wire document id, derived from the transmission intent (ADR-0052 §1)
 * @param reason which of the three endings this is
 * @param vendorErrorCode vendor document-level error code, verbatim, when the vendor stated one
 * @param vendorReason vendor-supplied rejection detail, verbatim, or the operator's recorded
 *     evidence for a manual resolution
 * @param rejectedAt when this side recorded the outcome
 * @param lines per-line detail, so a consumer can see which lines the vendor named; empty when
 *     the rejection was stated at document level only
 */
public record SupplierOrderRejectedV1(
        @NonNull UUID transmissionIntentId,
        @NonNull UUID purchaseOrderId,
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        @NonNull String documentId,
        @NonNull Reason reason,
        @Nullable String vendorErrorCode,
        @Nullable String vendorReason,
        @NonNull Instant rejectedAt,
        @NonNull List<SupplierOrderConfirmedLine> lines) {

    /** Event type of this payload on {@code supplier.events.v1}. */
    public static final String EVENT_TYPE = "supplier.order.rejected";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    /** Why the transmission ended without the vendor holding the order. */
    public enum Reason {
        /** The vendor answered and refused the order on its merits. */
        VENDOR_REJECTED,
        /**
         * An operator established the vendor never received it (ADR-0052 §4
         * {@code mark-not-received}). Re-ordering requires a new intent — this one is closed.
         */
        MANUAL_NOT_RECEIVED,
        /** An operator abandoned the transmission (ADR-0052 §4 {@code cancel}). */
        MANUAL_CANCELLED
    }

    public SupplierOrderRejectedV1 {
        Objects.requireNonNull(transmissionIntentId, "transmissionIntentId must not be null");
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId must not be null");
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(rejectedAt, "rejectedAt must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }
}
