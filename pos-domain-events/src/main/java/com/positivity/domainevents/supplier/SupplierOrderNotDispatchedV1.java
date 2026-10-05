package com.positivity.domainevents.supplier;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code supplier.order.notdispatched} v1 on {@code supplier.events.v1}
 * (ADR-0049 §3, #2492).
 *
 * <p>pos-supplier refused to dispatch a {@link SupplierOrderRequestedV1} command because the
 * vendor it names is not set up for electronic ordering: there is no vendor profile for the alias,
 * or the profile is disabled. The vendor has <strong>never seen</strong> the order. This is not a
 * {@link SupplierOrderRejectedV1}: a rejection is an outcome for a transmission intent, and no
 * intent was ever minted here, so there is no {@code transmissionIntentId}, no document id and
 * nothing a vendor could have refused. A revised, previously confirmed purchase order that hits a
 * disabled profile still has its earlier revision with the vendor, which is a second reason the
 * two outcomes must stay distinguishable.
 *
 * <h2>Correlation</h2>
 *
 * The aggregate id (and Kafka key) is the purchase order id and the envelope's aggregate version
 * is {@code requestedRevision}. The ordering domain MUST apply this event only while the purchase
 * order is still awaiting the answer to that revision, and ignore it otherwise: it travels on a
 * different partition key from the intent-keyed result events, so it can arrive after a later
 * result for the same order.
 *
 * <h2>No status events follow</h2>
 *
 * A never-dispatched PO gets no {@code supplier.orderstatus.changed} events, because no
 * transmission intent exists to poll. The ordering domain may re-send once the vendor profile is
 * configured or re-enabled; nothing in pos-supplier re-sends on its own.
 *
 * @param purchaseOrderId the purchase order whose request was not dispatched; the event aggregate
 *     id
 * @param supplierRef vendor profile alias the command named
 * @param vendorProfileId the disabled profile's id; {@code null} when no profile exists for the
 *     alias (never configured)
 * @param reason why the order was not dispatched
 * @param detail human-readable detail distinguishing never-configured from disabled
 * @param requestedRevision purchase-order revision the command asked to transmit; the event
 *     aggregate version
 * @param commandEventId event id of the {@code supplier.commands.v1} command being answered
 * @param occurredAt when pos-supplier recorded the refusal
 */
public record SupplierOrderNotDispatchedV1(
        @NonNull UUID purchaseOrderId,
        @NonNull String supplierRef,
        @Nullable UUID vendorProfileId,
        @NonNull Reason reason,
        @NonNull String detail,
        int requestedRevision,
        @NonNull UUID commandEventId,
        @NonNull Instant occurredAt) {

    /** Event type of this payload on {@code supplier.events.v1}. */
    public static final String EVENT_TYPE = "supplier.order.notdispatched";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    /** Why the order was not dispatched. */
    public enum Reason {
        /**
         * The vendor is not set up for electronic ordering: no profile exists for the alias, or the
         * profile is disabled. {@code detail} and {@code vendorProfileId} say which.
         */
        SUPPLIER_NOT_CONFIGURED
    }

    public SupplierOrderNotDispatchedV1 {
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        Objects.requireNonNull(commandEventId, "commandEventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (requestedRevision < 0) {
            throw new IllegalArgumentException("requestedRevision must be >= 0");
        }
    }
}
