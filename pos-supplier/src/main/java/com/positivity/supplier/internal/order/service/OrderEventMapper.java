package com.positivity.supplier.internal.order.service;

import com.positivity.domainevents.supplier.SupplierOrderConfirmedLine;
import com.positivity.domainevents.supplier.SupplierOrderStatusChangedV1;
import com.positivity.domainevents.supplier.SupplierOrderStatusDespatch;
import com.positivity.domainevents.supplier.SupplierOrderStatusLine;
import com.positivity.domainevents.supplier.SupplierOrderStatusSchedule;
import com.positivity.supplier.internal.domain.model.SupplierDeliverySchedule;
import com.positivity.supplier.internal.domain.model.SupplierDespatch;
import com.positivity.supplier.internal.domain.model.SupplierOrderResult;
import com.positivity.supplier.internal.domain.model.SupplierOrderStatusResult;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Translates the canonical supplier model into the cross-module event payloads
 * (ADR-0049 §3, ADR-0044).
 *
 * <h2>Why a translation at all, when the shapes look alike</h2>
 *
 * They look alike today and must be free to diverge tomorrow. The canonical model is
 * pos-supplier's own vocabulary and changes when the module's understanding of a capability
 * changes; the event payloads are a published contract other domains compile against, where a
 * field rename is a breaking change requiring a {@code schemaVersion} bump (ADR-0044 §3). Reusing
 * one record for both would make every internal refactor a contract change, and the first person
 * to discover that would be a consumer.
 *
 * <h2>Nothing is invented in translation</h2>
 *
 * Every mapping here is field-for-field and null-for-null. No default is applied, no absent
 * quantity becomes zero, no missing confirmed date is filled from a requested one. If a value is
 * not in the canonical model it is not in the event, because the vendor did not say it.
 */
final class OrderEventMapper {

    private OrderEventMapper() {}

    /** Canonical status lines to event status lines. */
    @NonNull
    static List<SupplierOrderStatusLine> toEventStatusLines(@NonNull List<SupplierOrderStatusResult.Line> lines) {
        return lines.stream()
                .map(line -> new SupplierOrderStatusLine(
                        line.lineId(),
                        line.customerLineItemNumber(),
                        line.articleEan(),
                        line.supplierArticleCode(),
                        line.buyersArticleId(),
                        line.description(),
                        line.orderedQuantity(),
                        line.requestedDeliveryDate(),
                        toEventSchedules(line.schedules()),
                        line.vendorErrorCode(),
                        line.vendorErrorText()))
                .toList();
    }

    /** Canonical order-response lines to event confirmation lines. */
    @NonNull
    static List<SupplierOrderConfirmedLine> toEventConfirmedLines(@NonNull List<SupplierOrderResult.Line> lines) {
        return lines.stream()
                .map(line -> new SupplierOrderConfirmedLine(
                        line.lineId(),
                        line.articleEan(),
                        line.supplierArticleCode(),
                        line.orderedQuantity(),
                        line.confirmedQuantity(),
                        line.killedQuantity(),
                        toEventSchedules(line.schedules()),
                        line.vendorErrorCode(),
                        line.vendorErrorText()))
                .toList();
    }

    /**
     * Canonical <em>status</em> lines to event confirmation lines.
     *
     * <p>Used when a confirmation is recovered from an order-status query rather than received on
     * an order response (ADR-0052 §3). The status document states an ordered quantity and
     * schedules but no {@code KilledQuantity}, so that field stays null: the vendor has not
     * refused a quantity, it has simply not been asked in a document that has a place to say so.
     * Confirmed quantity is totalled from the schedules, and stays null when none of them states
     * one.
     */
    @NonNull
    static List<SupplierOrderConfirmedLine> toEventConfirmedLinesFromStatus(
            @NonNull List<SupplierOrderStatusResult.Line> lines) {
        return lines.stream()
                .map(line -> new SupplierOrderConfirmedLine(
                        line.lineId(),
                        line.articleEan(),
                        line.supplierArticleCode(),
                        line.orderedQuantity(),
                        totalConfirmed(line.schedules()),
                        null,
                        toEventSchedules(line.schedules()),
                        line.vendorErrorCode(),
                        line.vendorErrorText()))
                .toList();
    }

    /**
     * Total confirmed across a line's schedules.
     *
     * @return the total, or null when no schedule stated a quantity — which is not a total of zero
     */
    @Nullable
    private static Integer totalConfirmed(List<SupplierDeliverySchedule> schedules) {
        Integer total = null;
        for (SupplierDeliverySchedule schedule : schedules) {
            if (schedule.confirmedQuantity() != null) {
                total = (total == null ? 0 : total) + schedule.confirmedQuantity();
            }
        }
        return total;
    }

    @NonNull
    static List<SupplierOrderStatusSchedule> toEventSchedules(@NonNull List<SupplierDeliverySchedule> schedules) {
        return schedules.stream()
                .map(schedule -> new SupplierOrderStatusSchedule(
                        schedule.deliveryDate(),
                        schedule.confirmedQuantity(),
                        schedule.cancelledQuantity(),
                        schedule.openQuantity(),
                        schedule.backorderedQuantity(),
                        schedule.scheduledQuantity(),
                        schedule.shippedQuantity(),
                        toEventDespatches(schedule.despatches())))
                .toList();
    }

    @NonNull
    static List<SupplierOrderStatusDespatch> toEventDespatches(@NonNull List<SupplierDespatch> despatches) {
        return despatches.stream()
                .map(despatch -> new SupplierOrderStatusDespatch(
                        despatch.despatchDate(),
                        // Verbatim, and verbatim is the whole point: this is the ASN handle an
                        // operator quotes to a vendor, and the join key if an ASN feed ever appears
                        // (ADR-0013/0027).
                        despatch.despatchAdviceDocumentId(),
                        despatch.despatchAdviceLineId(),
                        despatch.despatchedQuantity()))
                .toList();
    }

    /** Canonical status to the event's status enum. */
    static SupplierOrderStatusChangedV1.@NonNull Status toEventStatus(
            SupplierOrderStatusResult.@NonNull Status status) {
        return switch (status) {
            case NOT_FOUND -> SupplierOrderStatusChangedV1.Status.NOT_FOUND;
            case IN_PROGRESS -> SupplierOrderStatusChangedV1.Status.IN_PROGRESS;
            case CONFIRMED -> SupplierOrderStatusChangedV1.Status.CONFIRMED;
            case REJECTED -> SupplierOrderStatusChangedV1.Status.REJECTED;
        };
    }
}
