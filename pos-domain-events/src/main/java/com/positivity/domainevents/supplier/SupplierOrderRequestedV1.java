package com.positivity.domainevents.supplier;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code supplier.order.requested} v1 on {@code supplier.commands.v1}
 * (ADR-0049 §3, CAP-320 #1226).
 *
 * <p>A <strong>command</strong>: the domain that owns the purchase-order aggregate asks
 * pos-supplier to transmit one revision of one purchase order to one vendor. pos-supplier
 * answers with {@link SupplierOrderConfirmedV1} or {@link SupplierOrderRejectedV1} on
 * {@code supplier.events.v1}, and reports vendor state changes afterwards with
 * {@link SupplierOrderStatusChangedV1}.
 *
 * <h2>One command is one transmission intent</h2>
 *
 * Consuming this event mints an immutable {@code transmissionIntentId} (ADR-0052 §1) from which
 * the wire {@code DocumentID} derives deterministically. The producer does <strong>not</strong>
 * supply that id: minting it here is what makes a redelivered command a no-op rather than a
 * second physical order. Uniqueness is enforced across
 * {@code (vendorProfileId, intentType, purchaseOrderId, revision)}, so re-sending this exact
 * command never creates a second intent, while a genuine revision — a new {@code revision}, or
 * a {@code REPLACEMENT} after an operator marked a transmission not-received — is a new intent
 * with a new document id, by construction.
 *
 * <p>That is why {@code revision} and {@code intentType} are part of the payload and not
 * inferred: the producer is the only party that knows whether this is the same order stated
 * again or a different order to place.
 *
 * @param purchaseOrderId the purchase-order aggregate to transmit; the event aggregate id
 * @param revision revision of the purchase order this command transmits; {@code >= 0}, and part
 *     of the intent uniqueness tuple
 * @param intentType why this transmission exists relative to the purchase order (ADR-0052 §1)
 * @param supplierRef vendor profile alias to transmit to (ADR-0050 §1)
 * @param deliveryLocationId pos-location id of the receiving location, resolved by pos-supplier
 *     into the vendor's delivery account through the profile's mappings; a missing mapping is a
 *     configuration error, never a silently dropped consignee
 * @param purchaseOrderNumber the ordering domain's human-readable order number, carried to the
 *     vendor as an additional customer reference when the norm has a field for it; descriptive
 *     only
 * @param lines the lines to order; never empty
 */
public record SupplierOrderRequestedV1(
        @NonNull UUID purchaseOrderId,
        int revision,
        @NonNull IntentType intentType,
        @NonNull String supplierRef,
        @Nullable UUID deliveryLocationId,
        @Nullable String purchaseOrderNumber,
        @NonNull List<SupplierOrderRequestedLine> lines) {

    /** Event type of this payload on {@code supplier.commands.v1}. */
    public static final String EVENT_TYPE = "supplier.order.requested";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    /**
     * Why a transmission exists relative to its purchase order (ADR-0052 §1).
     *
     * <p>One purchase order legitimately produces several transmissions, and each is a distinct
     * intent with its own document id. This is the producer's statement of which case applies —
     * pos-supplier cannot tell a revision from a redelivery by looking at the payload.
     */
    public enum IntentType {
        /** First transmission of this purchase order to this vendor. */
        INITIAL,
        /** A changed revision of an order the vendor already holds. */
        REVISION,
        /** A fresh order replacing one that terminated without the vendor receiving it. */
        REPLACEMENT,
        /** Part of an order split across vendors or deliveries. */
        SPLIT
    }

    public SupplierOrderRequestedV1 {
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId must not be null");
        Objects.requireNonNull(intentType, "intentType must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }
}
