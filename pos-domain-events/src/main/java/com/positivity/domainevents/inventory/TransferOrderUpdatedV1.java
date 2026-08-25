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
 * @param shortCloseDisposition disposition chosen when the order was short-closed
 *     ({@code LOST_IN_TRANSIT} or {@code RETURNED_TO_SOURCE}, odoo-parity C3 issue #1039);
 *     null on every other transition. Transit losses carry NO {@code ScrapPostedV1} fact —
 *     no scrap document exists for them — so accounting consumers derive the shrinkage from
 *     this disposition plus the line remainders ({@code dispatchedQty - receivedQty})
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
        @Nullable String shortCloseDisposition,
        @NonNull Instant occurredAt) {

    public static final String EVENT_TYPE = "inventory.transfer-order.updated";
    public static final int SCHEMA_VERSION = 1;

    public TransferOrderUpdatedV1 {
        requireCoreFields(transferOrderId, status, sourceLocationId, destinationLocationId, lines, occurredAt);
        requireShortCloseInvariant(status, shortCloseDisposition);
        lines = List.copyOf(lines);
    }

    /**
     * The unconditional required-field sweep: every component that must always be present,
     * independent of any other field's value. Split out of the compact constructor to keep it
     * (and {@link #requireShortCloseInvariant}) each independently under the cognitive-complexity
     * threshold; the checks, their order and their messages are unchanged from the original
     * single-method form.
     */
    private static void requireCoreFields(
            @Nullable UUID transferOrderId,
            @Nullable String status,
            @Nullable UUID sourceLocationId,
            @Nullable UUID destinationLocationId,
            @Nullable List<LineSummary> lines,
            @Nullable Instant occurredAt) {
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
    }

    /**
     * The cross-field rule between {@code status} and {@code shortCloseDisposition}: the two must
     * imply each other exactly (see the record's javadoc). Called after {@link #requireCoreFields}
     * has already rejected a null/blank {@code status}, so it is safe to compare here.
     */
    private static void requireShortCloseInvariant(String status, @Nullable String shortCloseDisposition) {
        if (shortCloseDisposition != null) {
            if (!"SHORT_CLOSED".equals(status)) {
                throw new IllegalArgumentException("shortCloseDisposition is only valid for status SHORT_CLOSED");
            }
            if (!"LOST_IN_TRANSIT".equals(shortCloseDisposition)
                    && !"RETURNED_TO_SOURCE".equals(shortCloseDisposition)) {
                throw new IllegalArgumentException(
                        "shortCloseDisposition must be LOST_IN_TRANSIT or RETURNED_TO_SOURCE, got: "
                                + shortCloseDisposition);
            }
        }
        if ("SHORT_CLOSED".equals(status) && shortCloseDisposition == null) {
            throw new IllegalArgumentException("status SHORT_CLOSED requires a shortCloseDisposition");
        }
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
