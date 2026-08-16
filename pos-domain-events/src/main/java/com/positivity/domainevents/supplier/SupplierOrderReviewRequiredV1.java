package com.positivity.domainevents.supplier;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A transmission has stopped and needs a person, published on {@code supplier.events.v1}
 * (CAP-320 #1330, ADR-0052 §3/§4).
 *
 * <h2>Why this is not an outcome, and why it is still published</h2>
 *
 * pos-supplier deliberately publishes no confirmation or rejection when an intent escalates to
 * {@code MANUAL_REVIEW}: it does not know whether the vendor received the order, and saying either
 * would be an assertion nobody has made. That reasoning is why this event says neither.
 *
 * <p>What it does say is that the transmission has stopped and will not move again on its own.
 * Without it the ordering domain sees a request that looks in flight, indistinguishable from one
 * genuinely in progress — so a buyer waiting on goods waits on something that is not coming, and
 * the only record of why lives in a transmission log they have no reason to open. "Still open" and
 * "stuck pending a human" are different facts, and the second is the one somebody has to act on.
 *
 * <p>Deliberately carries no advice about what to do. Resolution is an operator's judgement made
 * against evidence pos-supplier holds (ADR-0052 §4), and a hint here would be this service
 * guessing at the very thing it escalated because it could not determine.
 *
 * @param transmissionIntentId the intent that stopped
 * @param purchaseOrderId      the purchase order it was transmitting
 * @param vendorProfileId      vendor profile the transmission was addressed to
 * @param supplierRef          that profile's alias, as at the time of the transmission
 * @param documentId           the vendor document id derived for this intent, which an operator
 *                             quotes to the vendor when asking whether it arrived
 * @param detail               why it escalated, as recorded for the operator; free text, never a
 *                             code to branch on
 * @param escalatedAt          when it stopped
 */
public record SupplierOrderReviewRequiredV1(
        @NonNull UUID transmissionIntentId,
        @NonNull UUID purchaseOrderId,
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        @NonNull String documentId,
        @Nullable String detail,
        @NonNull Instant escalatedAt) {

    /** Event type of this payload on {@code supplier.events.v1}. */
    public static final String EVENT_TYPE = "supplier.order.reviewrequired";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    public SupplierOrderReviewRequiredV1 {
        Objects.requireNonNull(transmissionIntentId, "transmissionIntentId must not be null");
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId must not be null");
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(escalatedAt, "escalatedAt must not be null");
    }
}
