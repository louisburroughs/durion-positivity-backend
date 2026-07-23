package com.positivity.domainevents.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a transfer order changed state (odoo-parity C1, issue #1035).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.transfer-order.updated"} — one fact per lifecycle transition
 * (create, approve, dispatch, receive, short-close, cancel). Each fact carries the full line
 * summary as of the transition so consumers can track in-transit remainders
 * ({@code dispatchedQty - receivedQty} per line) without querying pos-inventory.
 *
 * @param transferOrderId transfer order identifier (aggregate id of the fact)
 * @param status lifecycle status after the transition (DRAFT, APPROVED, DISPATCHED,
 *     PARTIALLY_RECEIVED, RECEIVED, SHORT_CLOSED, CANCELLED)
 * @param sourceLocationId source site the transfer ships from
 * @param sourceStorageLocationId bin-level source storage location, when bin-scoped
 * @param destinationLocationId destination site the transfer ships to
 * @param destinationStorageLocationId bin-level destination storage location, when bin-scoped
 * @param lines per-line quantity summary as of this transition
 * @param occurredAt when the transition was recorded
 */
public record TransferOrderUpdatedV1(
        @NonNull UUID transferOrderId,
        @NonNull String status,
        @NonNull UUID sourceLocationId,
        @Nullable UUID sourceStorageLocationId,
        @NonNull UUID destinationLocationId,
        @Nullable UUID destinationStorageLocationId,
        @NonNull List<LineSummary> lines,
        @NonNull Instant occurredAt) {

    public static final String EVENT_TYPE = "inventory.transfer-order.updated";
    public static final int SCHEMA_VERSION = 1;

    public TransferOrderUpdatedV1 {
        if (transferOrderId == null) {
            throw new IllegalArgumentException("transferOrderId must not be null");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        if (sourceLocationId == null) {
            throw new IllegalArgumentException("sourceLocationId must not be null");
        }
        if (destinationLocationId == null) {
            throw new IllegalArgumentException("destinationLocationId must not be null");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("lines must not be empty");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
        lines = List.copyOf(lines);
    }

    /**
     * Per-line quantity summary of a transfer order fact.
     *
     * @param sku stock-item identifier of the line (ledger stock-item string)
     * @param requestedQty quantity requested on the order line
     * @param dispatchedQty quantity dispatched from the source so far
     * @param receivedQty quantity received at the destination so far
     */
    public record LineSummary(@NonNull String sku, int requestedQty, int dispatchedQty, int receivedQty) {

        public LineSummary {
            if (sku == null || sku.isBlank()) {
                throw new IllegalArgumentException("sku must not be blank");
            }
            if (requestedQty <= 0) {
                throw new IllegalArgumentException("requestedQty must be positive");
            }
            if (dispatchedQty < 0 || receivedQty < 0) {
                throw new IllegalArgumentException("dispatchedQty and receivedQty must not be negative");
            }
        }
    }
}
