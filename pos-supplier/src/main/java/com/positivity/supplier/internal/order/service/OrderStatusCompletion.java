package com.positivity.supplier.internal.order.service;

import com.positivity.supplier.internal.domain.model.SupplierDeliverySchedule;
import com.positivity.supplier.internal.domain.model.SupplierDespatch;
import com.positivity.supplier.internal.domain.model.SupplierOrderStatusResult;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Decides when there is nothing left to learn from polling an order's status.
 *
 * <h2>{@code CONFIRMED} is not the stopping point</h2>
 *
 * The obvious reading of "polling stops at terminal states" is to stop when the vendor confirms.
 * That reading produces a purchase-order timeline with no delivery dates and no despatches on
 * it, because every fact #1318 exists to carry — confirmed schedules, despatch dates, ASN
 * references — arrives <em>after</em> confirmation. Confirmation is terminal for the
 * <em>transmission</em> (ADR-0052: never re-send). Fulfilment is what polling watches, and it
 * ends when the goods are accounted for.
 *
 * <h2>Absence never completes an order</h2>
 *
 * A schedule counts as settled only on a positive statement: an explicit {@code openQuantity} of
 * zero, or despatches totalling at least the confirmed quantity. A vendor that says nothing
 * about what remains has not said "nothing remains", so silence keeps polling alive rather than
 * ending it — the same rule the stock report applies to quantities (#1228). The cost of the
 * opposite mistake is a delivery nobody was told about.
 *
 * <p>Because silence keeps polling alive, a vendor that never states quantities would be polled
 * forever. That is bounded by age rather than by guesswork: see
 * {@link #isStale(LocalDate, LocalDate, int)}, which the poller applies alongside this and
 * records as a distinct stop reason, so "we stopped because it finished" and "we stopped because
 * we gave up" are never confused in the ledger.
 */
public final class OrderStatusCompletion {

    private OrderStatusCompletion() {}

    /** Why polling stopped, recorded on the intent so an operator can tell the cases apart. */
    public enum StopReason {
        /** The vendor refused the order; there is no fulfilment to watch. */
        VENDOR_REJECTED,
        /** Every line is scheduled and every schedule is accounted for. */
        FULLY_DESPATCHED,
        /**
         * Nothing has changed for long enough that further polling is vendor traffic without
         * information. Deliberately distinct from {@link #FULLY_DESPATCHED}: this one means we
         * stopped watching, not that the order arrived.
         */
        STALE,
        /** An operator resolved the transmission by hand (ADR-0052 §4). */
        MANUALLY_RESOLVED
    }

    /**
     * Whether the vendor's answer says the order is finished.
     *
     * @param result the vendor's current answer
     * @return the stop reason, or null while there is still something to learn
     */
    @Nullable
    public static StopReason completionOf(@NonNull SupplierOrderStatusResult result) {
        if (result.status() == SupplierOrderStatusResult.Status.REJECTED) {
            return StopReason.VENDOR_REJECTED;
        }
        if (result.status() == SupplierOrderStatusResult.Status.NOT_FOUND) {
            // Not a completion. What a NOT_FOUND means is a reconciliation question (ADR-0052 §3),
            // and answering it by silently retiring the order would hide the very case that most
            // needs a human.
            return null;
        }
        if (result.lines().isEmpty()) {
            return null;
        }
        for (SupplierOrderStatusResult.Line line : result.lines()) {
            if (line.schedules().isEmpty()) {
                return null;
            }
            for (SupplierDeliverySchedule schedule : line.schedules()) {
                if (!isSettled(schedule)) {
                    return null;
                }
            }
        }
        return StopReason.FULLY_DESPATCHED;
    }

    /**
     * Whether an order has gone quiet for long enough to stop asking.
     *
     * @param lastChange the day the vendor's answer last changed
     * @param today the current day
     * @param maxQuietDays how many days of no change to tolerate
     * @return true when polling should stop for staleness
     */
    public static boolean isStale(@Nullable LocalDate lastChange, @NonNull LocalDate today, int maxQuietDays) {
        if (lastChange == null || maxQuietDays <= 0) {
            return false;
        }
        return lastChange.plusDays(maxQuietDays).isBefore(today);
    }

    /**
     * A schedule is settled only on a positive statement from the vendor.
     *
     * <p>{@code openQuantity == 0} is the vendor saying nothing remains. Failing that, despatches
     * totalling the confirmed quantity say the same thing in the other direction. Everything else
     * — including a schedule with no quantities at all — leaves the schedule open.
     */
    private static boolean isSettled(SupplierDeliverySchedule schedule) {
        if (schedule.openQuantity() != null && schedule.openQuantity() == 0) {
            return true;
        }
        Integer confirmed = schedule.confirmedQuantity();
        if (confirmed == null) {
            return false;
        }
        Integer despatched = totalDespatched(schedule.despatches());
        return despatched != null && despatched >= confirmed;
    }

    /**
     * Total despatched across a schedule's despatches.
     *
     * @return the total, or null when no despatch stated a quantity — which is not a total of
     *     zero
     */
    @Nullable
    private static Integer totalDespatched(List<SupplierDespatch> despatches) {
        Integer total = null;
        for (SupplierDespatch despatch : despatches) {
            if (despatch.despatchedQuantity() != null) {
                total = (total == null ? 0 : total) + despatch.despatchedQuantity();
            }
        }
        return total;
    }
}
