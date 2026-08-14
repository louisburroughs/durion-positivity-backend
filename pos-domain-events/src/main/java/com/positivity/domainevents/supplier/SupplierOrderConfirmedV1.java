package com.positivity.domainevents.supplier;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code supplier.order.confirmed} v1 on {@code supplier.events.v1}
 * (ADR-0049 §3, CAP-320 #1226).
 *
 * <p>The vendor accepted the order. Published once per transmission intent, from the
 * transactional outbox in the transaction that recorded the acceptance, so a consumer never
 * sees a confirmation for a transmission this side subsequently forgot.
 *
 * @param transmissionIntentId the transmission intent that was accepted; the event aggregate id
 * @param purchaseOrderId originating purchase-order aggregate id
 * @param vendorProfileId vendor profile that holds the order (ADR-0050 §1 identity)
 * @param supplierRef profile alias at confirmation time; descriptive only
 * @param documentId our wire document id, derived from the transmission intent (ADR-0052 §1)
 * @param supplierOrderNumber the vendor's own order reference; an attribute, never a key
 *     (ADR-0013/0027). Null when the vendor confirmed without returning one
 * @param source how this confirmation was established
 * @param confirmedAt when this side recorded the acceptance
 * @param lines per-line confirmation, so partial acceptance is visible rather than inferred;
 *     empty when the vendor confirmed the order without itemising it
 */
public record SupplierOrderConfirmedV1(
        @NonNull UUID transmissionIntentId,
        @NonNull UUID purchaseOrderId,
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        @NonNull String documentId,
        @Nullable String supplierOrderNumber,
        @NonNull Source source,
        @NonNull Instant confirmedAt,
        @NonNull List<SupplierOrderConfirmedLine> lines) {

    /** Event type of this payload on {@code supplier.events.v1}. */
    public static final String EVENT_TYPE = "supplier.order.confirmed";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    /**
     * How the confirmation was established.
     *
     * <p>Carried because the three are not equally strong evidence, and a timeline that shows
     * them identically hides which orders a human vouched for. {@link #MANUAL_RESOLUTION} in
     * particular is an operator's assertion recorded under their name (ADR-0052 §4), not the
     * vendor's own answer.
     */
    public enum Source {
        /** The vendor's order response accepted the order. */
        ORDER_RESPONSE,
        /** An order-status query after an ambiguous outcome found the vendor holding it (ADR-0052 §3). */
        STATUS_RECONCILIATION,
        /** An operator confirmed it with the vendor and recorded the reference (ADR-0052 §4). */
        MANUAL_RESOLUTION
    }

    public SupplierOrderConfirmedV1 {
        Objects.requireNonNull(transmissionIntentId, "transmissionIntentId must not be null");
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId must not be null");
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(confirmedAt, "confirmedAt must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }
}
