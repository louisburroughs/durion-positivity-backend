package com.positivity.supplier.internal.domain.model;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Vendor outcome of an order-create exchange, mapping onto the
 * {@code supplier.order.confirmed} / {@code supplier.order.rejected} result events
 * (ADR-0049 §3). Ambiguous outcomes (timeout after send) never produce this record — they are
 * the orchestrator's reconciliation concern (ADR-0052 §3), not a codec result.
 *
 * <h2>Partial acceptance is represented, not summarised</h2>
 *
 * A vendor confirming three of five tyres is the ordinary case, not an error, and it is
 * expressed per line rather than as a document-level "partially accepted" flag: the ordering
 * domain has to know <em>which</em> lines fell short to decide what to do about them.
 *
 * @param status vendor decision at document level
 * @param supplierOrderNumber vendor-native order reference, carried as an attribute
 *     (ADR-0049 §2); present on confirmation when the vendor returns one
 * @param vendorReason vendor-supplied rejection reason, verbatim
 * @param vendorErrorCode vendor document-level error code, verbatim, when the vendor stated one
 * @param lines per-line outcome, in the order the vendor stated it; empty when the vendor
 *     answered at document level only
 */
public record SupplierOrderResult(
        @NonNull Status status,
        @Nullable String supplierOrderNumber,
        @Nullable String vendorReason,
        @Nullable String vendorErrorCode,
        @NonNull List<Line> lines) {

    public enum Status {
        CONFIRMED,
        REJECTED
    }

    /**
     * One line's outcome on the order response.
     *
     * @param lineId wire line identifier, verbatim
     * @param articleEan EAN/GTIN, when stated
     * @param supplierArticleCode the vendor's own article code, when stated
     * @param orderedQuantity quantity the vendor read as ordered
     * @param confirmedQuantity quantity the vendor confirmed; null when it stated none, which is
     *     not a confirmation of zero
     * @param killedQuantity quantity the vendor refused outright
     * @param schedules delivery schedules the vendor committed to; empty when it committed to
     *     none
     * @param vendorErrorCode vendor line-level error code, verbatim
     * @param vendorErrorText vendor line-level error text, verbatim
     */
    public record Line(
            @Nullable String lineId,
            @Nullable String articleEan,
            @Nullable String supplierArticleCode,
            @Nullable Integer orderedQuantity,
            @Nullable Integer confirmedQuantity,
            @Nullable Integer killedQuantity,
            @NonNull List<SupplierDeliverySchedule> schedules,
            @Nullable String vendorErrorCode,
            @Nullable String vendorErrorText) {

        public Line {
            Objects.requireNonNull(schedules, "schedules must not be null");
            schedules = List.copyOf(schedules);
        }
    }

    public SupplierOrderResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }
}
