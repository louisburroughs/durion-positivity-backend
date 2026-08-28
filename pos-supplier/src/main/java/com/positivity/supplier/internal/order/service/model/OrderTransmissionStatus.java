package com.positivity.supplier.internal.order.service.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Transmission state of one purchase-order transmission intent (ADR-0049 §2 — pos-supplier
 * holds transmission state only; the purchase-order aggregate stays with the ordering domain).
 *
 * <p>This is the operator-facing view of the ledger: what was sent, what the vendor said, and —
 * where a transmission is stuck — what a human needs in order to resolve it. Vendor-native
 * references ({@code supplierOrderNumber}) are attributes, never keys (ADR-0013/0027).
 *
 * <h2>What is deliberately not here</h2>
 *
 * No delivery schedules or despatches. Those travel to the domain that owns the purchase order as
 * {@code supplier.orderstatus.changed} facts and belong on its timeline (#1318); serving them
 * from here would create a second, competing source for the same information and invite a
 * synchronous cross-module read that ADR-0044 forbids. What this view carries instead is
 * {@code latestScheduledDeliveryDate} — one date, so an operator triaging a stuck transmission
 * can see at a glance whether the vendor has committed to anything.
 *
 * @param transmissionIntentId immutable idempotency identity of the transmission intent
 *     (ADR-0052 §1)
 * @param purchaseOrderId originating purchase-order aggregate id
 * @param purchaseOrderNumber the ordering domain's human-readable order number, when supplied
 * @param supplierRef vendor profile alias; descriptive only
 * @param documentId wire document id derived from {@code transmissionIntentId}; never blank
 * @param state current transmission state
 * @param supplierOrderNumber vendor-native order reference, when the vendor has assigned one
 * @param vendorErrorCode vendor error code, verbatim, when the vendor stated one
 * @param vendorReason vendor-supplied detail (e.g. rejection reason), verbatim
 * @param dispatchAttempts how many times a send has been attempted for this intent
 * @param latestScheduledDeliveryDate the latest delivery date the vendor has committed to across
 *     all lines; null when it has committed to none
 * @param lastStatusAt when the vendor's answer last changed
 * @param lastTransitionAt when the state last changed, when known
 * @param resolutionAction which ADR-0052 §4 action an operator took, when one was taken
 * @param resolvedBy who took it
 * @param resolvedAt when
 * @param failureDetail why this transmission needs attention, when it does
 */
public record OrderTransmissionStatus(
        @NonNull UUID transmissionIntentId,
        @NonNull UUID purchaseOrderId,
        @Nullable String purchaseOrderNumber,
        @NonNull String supplierRef,
        @NonNull String documentId,
        @NonNull State state,
        @Nullable String supplierOrderNumber,
        @Nullable String vendorErrorCode,
        @Nullable String vendorReason,
        int dispatchAttempts,
        @Nullable LocalDate latestScheduledDeliveryDate,
        @Nullable Instant lastStatusAt,
        @Nullable Instant lastTransitionAt,
        @Nullable String resolutionAction,
        @Nullable String resolvedBy,
        @Nullable Instant resolvedAt,
        @Nullable String failureDetail) {

    /** Transmission states (ADR-0052 §2). */
    public enum State {
        /** The intent exists but has not been dispatched to the vendor yet. */
        PENDING,
        /**
         * A send is in flight, or was in flight when this instance stopped. Never automatically
         * re-sent from here — recovery asks the vendor instead (ADR-0052 §2).
         */
        DISPATCHING,
        /** The vendor holds the order and has not decided yet. */
        SENT_AWAITING_RESULT,
        /** The vendor accepted the order. */
        CONFIRMED,
        /** The vendor rejected the order. */
        REJECTED,
        /**
         * A post-send ambiguous outcome awaits operator reconciliation (ADR-0052 §3). The only
         * state from which the resolution actions are available — and no action re-sends.
         */
        MANUAL_REVIEW,
        /** An operator established the vendor never received it; terminal (ADR-0052 §4). */
        FAILED,
        /** An operator abandoned the transmission; terminal (ADR-0052 §4). */
        CANCELLED
    }

    public OrderTransmissionStatus {
        Objects.requireNonNull(transmissionIntentId, "transmissionIntentId must not be null");
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        if (documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
    }
}
