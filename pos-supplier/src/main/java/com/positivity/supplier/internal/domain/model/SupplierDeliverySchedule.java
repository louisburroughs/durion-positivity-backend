package com.positivity.supplier.internal.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One delivery schedule a vendor committed to for an ordered line (CAP-320 #1226/#1318).
 *
 * <p>Shared by the order response and the order status: the vendor states delivery dates and
 * confirmed quantities in the same shape when it accepts an order and when it reports on one,
 * so the canonical model states it once. Fields an order response does not carry (open,
 * backordered, despatches) are simply null there and populate as the order progresses.
 *
 * <h2>A line has schedules, plural</h2>
 *
 * A vendor splitting one line across delivery dates is normal in this trade, not exceptional.
 * Nothing collapses this to one date per line — a collapse would have to pick a date, and every
 * available rule for picking one (earliest, latest, largest quantity) tells receiving something
 * the vendor did not say.
 *
 * <h2>{@code deliveryDate} is the vendor's commitment</h2>
 *
 * It is a different fact from the date we requested, which stays on the line. A null here means
 * the vendor confirmed without committing to a date, <em>not</em> that it accepted ours.
 *
 * <p>Whether the norm's {@code DeliveryDate} means ship-from or arrive-at is unstated by the
 * spec, and the two differ by transit time. The canonical model therefore records it as the
 * vendor's stated delivery date and leaves the interpretation to a consumer that knows the
 * vendor's confirmed semantics.
 *
 * @param deliveryDate the delivery date the vendor committed to; null when it committed to none
 * @param confirmedQuantity quantity confirmed for this schedule
 * @param cancelledQuantity quantity cancelled
 * @param openQuantity quantity still outstanding
 * @param backorderedQuantity quantity on backorder
 * @param scheduledQuantity quantity scheduled
 * @param shippedQuantity quantity reported shipped for this schedule
 * @param despatches actual despatches against this schedule, in the order stated; empty when
 *     nothing has despatched yet — which is a state, not a gap
 */
public record SupplierDeliverySchedule(
        @Nullable LocalDate deliveryDate,
        @Nullable Integer confirmedQuantity,
        @Nullable Integer cancelledQuantity,
        @Nullable Integer openQuantity,
        @Nullable Integer backorderedQuantity,
        @Nullable Integer scheduledQuantity,
        @Nullable Integer shippedQuantity,
        @NonNull List<SupplierDespatch> despatches) {

    public SupplierDeliverySchedule {
        Objects.requireNonNull(despatches, "despatches must not be null");
        despatches = List.copyOf(despatches);
    }

    /**
     * A schedule stating only a date and a confirmed quantity — the shape an order response
     * carries.
     */
    @NonNull
    public static SupplierDeliverySchedule confirmed(
            @Nullable LocalDate deliveryDate, @Nullable Integer confirmedQuantity) {
        return new SupplierDeliverySchedule(deliveryDate, confirmedQuantity, null, null, null, null, null, List.of());
    }
}
