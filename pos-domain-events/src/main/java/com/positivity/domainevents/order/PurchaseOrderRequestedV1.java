package com.positivity.domainevents.order;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A request that pos-order place a purchase order, published on {@code order.commands.v1}
 * (CAP-320 #1334, ADR-0044 R1).
 *
 * <h2>Why a command rather than a call</h2>
 *
 * Replenishment decides that stock needs ordering; ordering is pos-order's to do. Before the split
 * pos-inventory simply called its own purchase-order service, which is exactly the cross-domain
 * write ADR-0044 R1 removes. It now asks, and the answer comes back as the ordinary
 * {@code purchaseorder.updated} fact.
 *
 * <h2>Why the requester supplies the identity</h2>
 *
 * {@code purchaseOrderId} is minted by the requester and used as the order's primary key. That
 * makes "exactly one order per conversion" a uniqueness constraint rather than an application
 * check: a redelivered command, a retried publish, or a duplicated conversion all collide on the
 * same key and the second one is a no-op. Deriving identity on the receiving side instead would
 * mean two deliveries of one request could produce two orders, and the duplicate would be
 * indistinguishable from a buyer genuinely ordering the same goods twice.
 *
 * <p>The order <em>number</em> is not supplied: that is pos-order's to assign, from its own
 * sequence, and the requester learns it from the fact.
 *
 * @param purchaseOrderId      identity the order will be created with, minted by the requester
 * @param vendorId             vendor to order from
 * @param currency             ISO 4217 currency for the order's monetary figures
 * @param shipToLocationId     receiving location
 * @param poDate               order date
 * @param expectedDeliveryDate when delivery is expected; null when undated
 * @param requestedBy          who asked for the order
 * @param comment              free-text note recorded on the order, e.g. what it was converted from
 * @param requestedAt          when the request was raised
 * @param lines                what to order
 */
public record PurchaseOrderRequestedV1(
        @NonNull UUID purchaseOrderId,
        @NonNull UUID vendorId,
        @Nullable String currency,
        @Nullable UUID shipToLocationId,
        @Nullable LocalDate poDate,
        @Nullable LocalDate expectedDeliveryDate,
        @Nullable String requestedBy,
        @Nullable String comment,
        @NonNull Instant requestedAt,
        @NonNull List<PurchaseOrderRequestedLine> lines) {

    /** Event type of this payload on {@code order.commands.v1}. */
    public static final String EVENT_TYPE = "purchaseorder.requested";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    public PurchaseOrderRequestedV1 {
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId must not be null");
        Objects.requireNonNull(vendorId, "vendorId must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }
}
