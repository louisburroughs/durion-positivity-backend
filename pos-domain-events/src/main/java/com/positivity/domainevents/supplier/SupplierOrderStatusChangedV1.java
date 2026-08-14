package com.positivity.domainevents.supplier;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code supplier.orderstatus.changed} v1 on {@code supplier.events.v1}
 * (ADR-0049 §3; CAP-320 #1226, extended by #1318).
 *
 * <p>Published by pos-supplier when a vendor's answer about a transmitted purchase order
 * <em>changes</em> — never on every poll. The consuming domain owns the purchase-order
 * aggregate and renders this on the purchase-order timeline; pos-supplier holds transmission
 * state only (ADR-0049 §2).
 *
 * <h2>This is what receiving gets instead of shipment tracking</h2>
 *
 * EDIWheel shipment tracking was withdrawn (#1313, ADR-0049 amendment 2026-08-14): it is a
 * logistics-provider-to-supplier exchange Durion is not party to. Order status is not a
 * substitute feed bolted on afterwards — it is a feed we are unambiguously a party to, and it
 * already carries what receiving needs: per-line confirmed delivery dates, actual despatch
 * dates, and the despatch advice (ASN) reference for each despatch. Everything arrival-shaped
 * on the timeline comes from here.
 *
 * <h2>Nothing is flattened to one date per order</h2>
 *
 * The tree is line → schedule → despatch, and all three levels survive to the consumer. A line
 * may carry several confirmed schedules and a schedule several despatches; partial fulfilment
 * is the modelled normal case. The three date kinds — requested, confirmed, despatched — stay
 * distinct and separately nullable, with no coalescing and no defaulting (see
 * {@link SupplierOrderStatusLine}).
 *
 * <h2>Absence is not zero</h2>
 *
 * The same rule the stock report enforces (#1228) holds here. A quantity the vendor did not
 * state decodes to null and stays null; a line with no schedule is a line the vendor has not
 * scheduled, not a line scheduled for nothing.
 *
 * @param transmissionIntentId the transmission intent this status describes; the event
 *     aggregate id (ADR-0052 §1)
 * @param purchaseOrderId originating purchase-order aggregate id, so a consumer can place this
 *     on the right timeline
 * @param vendorProfileId vendor profile that holds the order (ADR-0050 §1 identity)
 * @param supplierRef profile alias at observation time; descriptive only, never a join key
 * @param documentId our wire document id, derived from the transmission intent (ADR-0052 §1)
 * @param supplierOrderNumber the vendor's own order reference; an attribute, never a key
 *     (ADR-0013/0027)
 * @param status the vendor-known state of the order at {@code observedAt}
 * @param vendorReason vendor-supplied detail (e.g. a rejection reason), verbatim
 * @param observedAt when pos-supplier read this state from the vendor
 * @param lines per-line status, in the order the vendor stated it; empty when the vendor
 *     answered about the order without itemising it
 */
public record SupplierOrderStatusChangedV1(
        @NonNull UUID transmissionIntentId,
        @NonNull UUID purchaseOrderId,
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        @NonNull String documentId,
        @Nullable String supplierOrderNumber,
        @NonNull Status status,
        @Nullable String vendorReason,
        @NonNull Instant observedAt,
        @NonNull List<SupplierOrderStatusLine> lines) {

    /** Event type of this payload on {@code supplier.events.v1}. */
    public static final String EVENT_TYPE = "supplier.orderstatus.changed";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    /**
     * Vendor-known state of the order.
     *
     * <p>{@link #NOT_FOUND} is a real answer, not a failure to get one: it is the vendor saying
     * it holds no such document, which is the reconciliation primitive of ADR-0052 §3. A
     * transport failure never produces this event at all.
     */
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

    public SupplierOrderStatusChangedV1 {
        Objects.requireNonNull(transmissionIntentId, "transmissionIntentId must not be null");
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId must not be null");
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }
}
