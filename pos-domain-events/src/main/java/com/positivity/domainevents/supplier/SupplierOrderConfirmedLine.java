package com.positivity.domainevents.supplier;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One line as the vendor confirmed it on the order response (ADR-0049 §3, CAP-320 #1226).
 *
 * <h2>Partial acceptance is explicit, not inferred</h2>
 *
 * A vendor commonly confirms less than was ordered. {@code orderedQuantity},
 * {@code confirmedQuantity} and {@code killedQuantity} are carried separately so a consumer can
 * see the shortfall rather than deducing it, and each is separately nullable: a vendor that
 * stated no confirmed quantity has not confirmed zero. A line with a stated
 * {@code confirmedQuantity} of {@code 0} is a genuine refusal of that line and reads very
 * differently from a line the vendor said nothing about.
 *
 * <p>{@code schedules} reuses {@link SupplierOrderStatusSchedule} rather than defining a
 * near-identical order-response type: the order response states delivery dates and confirmed
 * quantities in the same shape order status later reports them, and a consumer that renders a
 * confirmation should not have to learn two vocabularies for one fact. Fields the order
 * response does not carry (open, backordered, despatches) arrive null here and populate as the
 * order progresses through {@link SupplierOrderStatusChangedV1}.
 *
 * @param lineId the wire line identifier, verbatim
 * @param articleEan EAN/GTIN of the article, when stated
 * @param supplierArticleCode the vendor's own article code, when stated
 * @param orderedQuantity quantity the vendor read as ordered
 * @param confirmedQuantity quantity the vendor confirmed
 * @param killedQuantity quantity the vendor refused outright
 * @param schedules delivery schedules the vendor committed to for this line; empty when it
 *     committed to none
 * @param vendorErrorCode vendor line-level error code, verbatim, when the vendor flagged one
 * @param vendorErrorText vendor line-level error text, verbatim, when the vendor stated one
 */
public record SupplierOrderConfirmedLine(
        @Nullable String lineId,
        @Nullable String articleEan,
        @Nullable String supplierArticleCode,
        @Nullable Integer orderedQuantity,
        @Nullable Integer confirmedQuantity,
        @Nullable Integer killedQuantity,
        @NonNull List<SupplierOrderStatusSchedule> schedules,
        @Nullable String vendorErrorCode,
        @Nullable String vendorErrorText) {

    public SupplierOrderConfirmedLine {
        Objects.requireNonNull(schedules, "schedules must not be null");
        schedules = List.copyOf(schedules);
    }

    /**
     * Whether the vendor confirmed strictly less than was ordered.
     *
     * <p>False when either quantity is unstated: an unknown is not a shortfall, and a consumer
     * that treats it as one would raise a backorder against a vendor's silence.
     */
    public boolean isPartiallyAccepted() {
        return orderedQuantity != null && confirmedQuantity != null && confirmedQuantity < orderedQuantity;
    }
}
