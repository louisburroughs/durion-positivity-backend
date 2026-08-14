package com.positivity.domainevents.supplier;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One confirmed delivery schedule for an ordered line, carried by
 * {@link SupplierOrderStatusChangedV1} (durion-positivity-backend#1318).
 *
 * <p>A line may carry several schedules — the vendor splitting an order across delivery dates
 * is normal, not exceptional — and each schedule may have despatched several times. Nothing
 * here is collapsed to one date or one quantity per line.
 *
 * <h2>{@code deliveryDate} is the vendor's commitment, not our request</h2>
 *
 * The requested date lives on {@link SupplierOrderStatusLine#requestedDeliveryDate()} and stays
 * there. A missing {@code deliveryDate} means the vendor has confirmed the line without
 * committing to a date; it does <strong>not</strong> mean the requested date was accepted, and
 * substituting one for the other would show receiving a promise the vendor never made.
 *
 * <p>What {@code deliveryDate} means in transit terms — ship-from or arrive-at — is not stated
 * by the EDIWheel Order Status norm, and the two differ by transit time. Consumers should
 * render it as the vendor's stated delivery date rather than asserting an arrival at the dock
 * until a vendor's semantics are confirmed and recorded.
 *
 * <h2>Absence is not zero</h2>
 *
 * Every quantity is separately nullable with no default. A schedule with no stated
 * {@code openQuantity} is not a schedule with nothing outstanding — it is a schedule where the
 * vendor said nothing about what remains.
 *
 * @param deliveryDate the delivery date the vendor committed to; null when it committed to none
 * @param confirmedQuantity quantity the vendor confirmed for this schedule
 * @param cancelledQuantity quantity the vendor cancelled
 * @param openQuantity quantity still outstanding after any despatches
 * @param backorderedQuantity quantity the vendor put on backorder
 * @param scheduledQuantity quantity the vendor scheduled
 * @param shippedQuantity quantity the vendor reported shipped for this schedule
 * @param despatches actual despatches against this schedule, in the order the vendor stated
 *     them; empty when the vendor has despatched nothing yet
 */
public record SupplierOrderStatusSchedule(
        @Nullable LocalDate deliveryDate,
        @Nullable Integer confirmedQuantity,
        @Nullable Integer cancelledQuantity,
        @Nullable Integer openQuantity,
        @Nullable Integer backorderedQuantity,
        @Nullable Integer scheduledQuantity,
        @Nullable Integer shippedQuantity,
        @NonNull List<SupplierOrderStatusDespatch> despatches) {

    public SupplierOrderStatusSchedule {
        Objects.requireNonNull(despatches, "despatches must not be null");
        despatches = List.copyOf(despatches);
    }

    /** Whether the vendor has reported any despatch against this schedule. */
    public boolean hasDespatched() {
        return !despatches.isEmpty();
    }
}
