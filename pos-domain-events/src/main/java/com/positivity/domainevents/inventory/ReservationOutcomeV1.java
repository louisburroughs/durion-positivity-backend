package com.positivity.domainevents.inventory;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: the outcome of a demand line's reservation request (CAP #1315).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.reservation.outcome.recorded"} — one fact per reservation-request
 * command processed. The requesting module (pos-order at checkout, pos-workorder at part-issue)
 * consumes this to resolve the demand line out of its provisional state: {@code covered} true
 * means owned ATP at the requested location currently covers the full quantity; false means it
 * does not and a backorder ({@code backorderId}) was opened for the shortfall.
 *
 * <h2>Not a hard allocation</h2>
 *
 * {@code covered = true} means the site-level ledger currently has enough on-hand, net of other
 * hard allocations there, for this quantity — the same test {@code BackorderService} already uses
 * to decide whether a shortfall exists. It does not mean stock has been pinned to a shelf: that is
 * a separate, later action ({@code promoteReservationAllocation}) belonging to the actual pick
 * flow, which knows a storage location this fact's producer does not.
 *
 * @param reservationId the reservation this outcome is for
 * @param workorderLineId workorder line the demand is for, when demand is from a workorder
 * @param salesOrderLineId sales-order line the demand is for, when demand is from a sales order
 * @param stockItemId stock-item identifier that was requested
 * @param requiredQuantity quantity requested
 * @param covered whether owned ATP at the requested location currently covers the full quantity
 * @param backorderId the backorder opened for the shortfall, when {@code covered} is false
 * @param occurredAt when the outcome was determined
 */
public record ReservationOutcomeV1(
        @NonNull UUID reservationId,
        @Nullable UUID workorderLineId,
        @Nullable UUID salesOrderLineId,
        @NonNull String stockItemId,
        int requiredQuantity,
        boolean covered,
        @Nullable UUID backorderId,
        @NonNull Instant occurredAt) {

    public static final String EVENT_TYPE = "inventory.reservation.outcome.recorded";
    public static final int SCHEMA_VERSION = 1;

    public ReservationOutcomeV1 {
        if (reservationId == null) {
            throw new IllegalArgumentException("reservationId must not be null");
        }
        if ((workorderLineId == null) == (salesOrderLineId == null)) {
            throw new IllegalArgumentException("exactly one of workorderLineId/salesOrderLineId must be set");
        }
        if (stockItemId == null || stockItemId.isBlank()) {
            throw new IllegalArgumentException("stockItemId must not be blank");
        }
        if (requiredQuantity <= 0) {
            throw new IllegalArgumentException("requiredQuantity must be positive");
        }
        if (!covered && backorderId == null) {
            throw new IllegalArgumentException("an uncovered outcome must carry the backorderId it opened");
        }
        if (covered && backorderId != null) {
            throw new IllegalArgumentException("a covered outcome must not carry a backorderId");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }
}
