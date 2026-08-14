package com.positivity.supplier.internal.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.internal.domain.model.SupplierDeliverySchedule;
import com.positivity.supplier.internal.domain.model.SupplierDespatch;
import com.positivity.supplier.internal.domain.model.SupplierOrderStatusResult;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("When order status polling has nothing left to learn (#1318)")
class OrderStatusCompletionTest {

    private static SupplierOrderStatusResult result(
            SupplierOrderStatusResult.Status status, SupplierDeliverySchedule... schedules) {
        return new SupplierOrderStatusResult(
                "DUR0198F3A24C7E7A1B9C2D3E4F5A6B7C8D",
                status,
                "MICH-1",
                null,
                null,
                null,
                List.of(new SupplierOrderStatusResult.Line(
                        "000001",
                        null,
                        "3528709999083",
                        null,
                        null,
                        null,
                        10,
                        LocalDate.of(2026, 8, 10),
                        List.of(schedules),
                        null,
                        null)));
    }

    private static SupplierDespatch despatch(int quantity) {
        return new SupplierDespatch(LocalDate.of(2026, 8, 11), "ASN-1", null, quantity);
    }

    @Test
    void doesNotStopAtConfirmation() {
        // The mistake this class exists to prevent. A confirmed order with a schedule that has not
        // despatched is where every fact #1318 carries is still to come; stopping here produces a
        // purchase-order timeline with no arrivals on it.
        SupplierOrderStatusResult confirmedNotDespatched = result(
                SupplierOrderStatusResult.Status.CONFIRMED,
                new SupplierDeliverySchedule(LocalDate.of(2026, 8, 12), 10, null, null, null, null, null, List.of()));

        assertThat(OrderStatusCompletion.completionOf(confirmedNotDespatched)).isNull();
    }

    @Test
    void stopsWhenTheVendorRejectedTheOrder() {
        assertThat(OrderStatusCompletion.completionOf(result(SupplierOrderStatusResult.Status.REJECTED)))
                .isEqualTo(OrderStatusCompletion.StopReason.VENDOR_REJECTED);
    }

    @Test
    void stopsWhenDespatchesCoverTheConfirmedQuantity() {
        SupplierOrderStatusResult fullyDespatched = result(
                SupplierOrderStatusResult.Status.CONFIRMED,
                new SupplierDeliverySchedule(
                        LocalDate.of(2026, 8, 12), 10, null, null, null, null, 10, List.of(despatch(6), despatch(4))));

        assertThat(OrderStatusCompletion.completionOf(fullyDespatched))
                .isEqualTo(OrderStatusCompletion.StopReason.FULLY_DESPATCHED);
    }

    @Test
    void stopsWhenTheVendorStatesNothingIsOpen() {
        SupplierOrderStatusResult closed = result(
                SupplierOrderStatusResult.Status.CONFIRMED,
                new SupplierDeliverySchedule(LocalDate.of(2026, 8, 12), null, null, 0, null, null, null, List.of()));

        assertThat(OrderStatusCompletion.completionOf(closed))
                .isEqualTo(OrderStatusCompletion.StopReason.FULLY_DESPATCHED);
    }

    @Test
    void keepsPollingWhileQuantityIsStillOpen() {
        SupplierOrderStatusResult partial = result(
                SupplierOrderStatusResult.Status.CONFIRMED,
                new SupplierDeliverySchedule(
                        LocalDate.of(2026, 8, 12), 10, null, 4, null, null, 6, List.of(despatch(6))));

        assertThat(OrderStatusCompletion.completionOf(partial)).isNull();
    }

    @Test
    void keepsPollingWhenOneScheduleOfTwoIsStillOutstanding() {
        SupplierOrderStatusResult split = result(
                SupplierOrderStatusResult.Status.CONFIRMED,
                new SupplierDeliverySchedule(
                        LocalDate.of(2026, 8, 12), 8, null, 0, null, null, 8, List.of(despatch(8))),
                new SupplierDeliverySchedule(LocalDate.of(2026, 8, 26), 4, null, 4, null, null, null, List.of()));

        assertThat(OrderStatusCompletion.completionOf(split)).isNull();
    }

    @Test
    void neverCompletesAnOrderOnTheVendorsSilence() {
        // A schedule stating no quantities at all has not said "nothing remains". Treating silence
        // as completion is how a delivery arrives that nobody was told about.
        SupplierOrderStatusResult silent = result(
                SupplierOrderStatusResult.Status.CONFIRMED,
                new SupplierDeliverySchedule(LocalDate.of(2026, 8, 12), null, null, null, null, null, null, List.of()));

        assertThat(OrderStatusCompletion.completionOf(silent)).isNull();
    }

    @Test
    void neverCompletesALineTheVendorHasNotScheduled() {
        SupplierOrderStatusResult unscheduled = result(SupplierOrderStatusResult.Status.CONFIRMED);

        assertThat(OrderStatusCompletion.completionOf(unscheduled)).isNull();
    }

    @Test
    void neverCompletesOnNotFound() {
        // What a NOT_FOUND means is a reconciliation question, and silently retiring the order
        // would hide the one case that most needs a human.
        assertThat(OrderStatusCompletion.completionOf(result(SupplierOrderStatusResult.Status.NOT_FOUND)))
                .isNull();
    }

    @Test
    void givesUpOnAnOrderThatHasBeenQuietTooLong() {
        // Because silence never completes an order, a vendor that never states quantities would
        // otherwise be polled forever. Bounded by age, and recorded as STALE rather than as a
        // completion, so "it arrived" and "we stopped watching" stay distinguishable.
        assertThat(OrderStatusCompletion.isStale(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 14), 45))
                .isTrue();
        assertThat(OrderStatusCompletion.isStale(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 14), 45))
                .isFalse();
    }

    @Test
    void doesNotGiveUpOnAnOrderThatHasNeverBeenAnswered() {
        assertThat(OrderStatusCompletion.isStale(null, LocalDate.of(2026, 8, 14), 45))
                .isFalse();
    }
}
