package com.positivity.domainevents.supplier;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One ordered line as the vendor currently reports it, carried by
 * {@link SupplierOrderStatusChangedV1} (durion-positivity-backend#1318).
 *
 * <p>Line-level granularity survives to the event on purpose: a consumer must be able to say
 * which line is arriving when, and in what quantity, without inferring it from an order-level
 * summary that cannot express a split delivery.
 *
 * <h2>Three date kinds, kept distinct</h2>
 *
 * <ol>
 *   <li><strong>requested</strong> — {@link #requestedDeliveryDate()} here: what we asked for.
 *   <li><strong>confirmed</strong> — {@link SupplierOrderStatusSchedule#deliveryDate()}: what
 *       the vendor committed to.
 *   <li><strong>despatched</strong> — {@link SupplierOrderStatusDespatch#despatchDate()}: when
 *       it actually left.
 * </ol>
 *
 * They are separately nullable and never coalesced. A missing confirmed date is not the
 * requested date; an absent despatch is not a despatch of zero. Collapsing them would let a
 * receiving screen show a vendor commitment that only ever existed as our own request.
 *
 * @param lineId the vendor-facing line identifier as stated on the wire ({@code LineID}),
 *     verbatim and never parsed
 * @param customerLineItemNumber our own line reference echoed back by the vendor, when stated
 * @param articleEan EAN/GTIN of the article, when stated
 * @param supplierArticleCode the vendor's own article code, when stated; a display alias, never
 *     an identifier (ADR-0013/0027)
 * @param buyersArticleId our article code as the vendor holds it, when stated
 * @param description vendor article description, when stated
 * @param orderedQuantity quantity ordered on this line, as the vendor reports it
 * @param requestedDeliveryDate the delivery date we asked for; null when none was stated
 * @param schedules confirmed delivery schedules for this line, in the order the vendor stated
 *     them; empty when the vendor has confirmed no schedule yet
 * @param vendorErrorCode vendor line-level error code, verbatim, when the vendor flagged one
 * @param vendorErrorText vendor line-level error text, verbatim, when the vendor stated one
 */
public record SupplierOrderStatusLine(
        @Nullable String lineId,
        @Nullable String customerLineItemNumber,
        @Nullable String articleEan,
        @Nullable String supplierArticleCode,
        @Nullable String buyersArticleId,
        @Nullable String description,
        @Nullable Integer orderedQuantity,
        @Nullable LocalDate requestedDeliveryDate,
        @NonNull List<SupplierOrderStatusSchedule> schedules,
        @Nullable String vendorErrorCode,
        @Nullable String vendorErrorText) {

    public SupplierOrderStatusLine {
        Objects.requireNonNull(schedules, "schedules must not be null");
        schedules = List.copyOf(schedules);
    }

    /**
     * Whether the vendor has confirmed any delivery schedule for this line.
     *
     * <p>False is a meaningful state, not a gap: an accepted line awaiting scheduling looks
     * exactly like this, and a consumer should render "no date yet" rather than falling back to
     * {@link #requestedDeliveryDate()}.
     */
    public boolean hasConfirmedSchedule() {
        return !schedules.isEmpty();
    }

    /** Whether any schedule on this line reports at least one despatch. */
    public boolean hasDespatched() {
        return schedules.stream().anyMatch(SupplierOrderStatusSchedule::hasDespatched);
    }
}
