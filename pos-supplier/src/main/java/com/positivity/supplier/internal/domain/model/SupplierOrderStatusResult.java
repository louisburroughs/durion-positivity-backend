package com.positivity.supplier.internal.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Vendor answer to an order-status query by wire document id.
 *
 * <p>Two jobs, both load-bearing:
 *
 * <ol>
 *   <li><strong>Reconciliation primitive (ADR-0052 §3).</strong> After an ambiguous order-create
 *       outcome the orchestrator asks the vendor whether it knows the document;
 *       {@link Status#NOT_FOUND} versus a known state decides between safe re-dispatch and
 *       {@code MANUAL_REVIEW}. This is why a transport failure must never be mapped to
 *       {@code NOT_FOUND}: "the vendor says it has no such order" and "we could not ask" look
 *       identical to a consumer and differ by one duplicate delivery.
 *   <li><strong>Arrival expectations (CAP-320 #1318).</strong> The status document carries, per
 *       ordered line, the delivery dates the vendor committed to and the despatches it has made
 *       against them. That is what receiving needs and what the withdrawn shipment feed (#1313)
 *       was meant to provide; it is carried here rather than dropped on the floor.
 * </ol>
 *
 * <h2>Three date kinds, never coalesced</h2>
 *
 * {@link Line#requestedDeliveryDate()} is what we asked for,
 * {@link SupplierDeliverySchedule#deliveryDate()} is what the vendor committed to, and
 * {@link SupplierDespatch#despatchDate()} is when it actually left. All three are separately
 * nullable and none substitutes for another. Filling a missing confirmed date from the
 * requested one would show receiving a vendor commitment that only ever existed as our request.
 *
 * @param documentId the wire document id that was queried; never blank
 * @param status vendor-known state of the order
 * @param supplierOrderNumber vendor-native order reference, when the vendor knows the order;
 *     an attribute, never a key (ADR-0013/0027)
 * @param vendorReason vendor-supplied detail (e.g. rejection reason), verbatim
 * @param vendorErrorCode vendor document-level error code, verbatim, when the vendor stated one
 * @param orderDate the order date the vendor holds for this document, when stated
 * @param lines per-line status; empty when the vendor answered about the order without
 *     itemising it, which includes every {@link Status#NOT_FOUND} answer
 */
public record SupplierOrderStatusResult(
        @NonNull String documentId,
        @NonNull Status status,
        @Nullable String supplierOrderNumber,
        @Nullable String vendorReason,
        @Nullable String vendorErrorCode,
        @Nullable LocalDate orderDate,
        @NonNull List<Line> lines) {

    public enum Status {
        /** The vendor has no order for this document id. */
        NOT_FOUND,
        /** The vendor knows the order and is still processing it. */
        IN_PROGRESS,
        /** The vendor accepted the order. */
        CONFIRMED,
        /** The vendor rejected the order. */
        REJECTED
    }

    /**
     * One ordered line as the vendor currently reports it.
     *
     * @param lineId wire line identifier ({@code LineID}), verbatim and never parsed
     * @param customerLineItemNumber our own line reference echoed back, when stated
     * @param articleEan EAN/GTIN, when stated
     * @param supplierArticleCode the vendor's own article code, when stated; a display alias
     * @param buyersArticleId our article code as the vendor holds it, when stated
     * @param description vendor article description, when stated
     * @param orderedQuantity quantity the vendor reports as ordered
     * @param requestedDeliveryDate the delivery date we asked for; null when none was stated
     * @param schedules confirmed delivery schedules, in the order stated; empty when the vendor
     *     has confirmed no schedule yet — an accepted-but-unscheduled line looks exactly like
     *     this, and it is not the same as a line with no dates
     * @param vendorErrorCode vendor line-level error code, verbatim
     * @param vendorErrorText vendor line-level error text, verbatim
     */
    public record Line(
            @Nullable String lineId,
            @Nullable String customerLineItemNumber,
            @Nullable String articleEan,
            @Nullable String supplierArticleCode,
            @Nullable String buyersArticleId,
            @Nullable String description,
            @Nullable Integer orderedQuantity,
            @Nullable LocalDate requestedDeliveryDate,
            @NonNull List<SupplierDeliverySchedule> schedules,
            @Nullable String vendorErrorCode,
            @Nullable String vendorErrorText) {

        public Line {
            Objects.requireNonNull(schedules, "schedules must not be null");
            schedules = List.copyOf(schedules);
        }
    }

    public SupplierOrderStatusResult {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        if (documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        lines = List.copyOf(lines);
    }

    /**
     * The vendor's answer when it holds no such document — the ADR-0052 §3 "does not know it"
     * branch.
     *
     * @param documentId the document id that was queried
     * @return a line-less {@code NOT_FOUND} result
     */
    @NonNull
    public static SupplierOrderStatusResult notFound(@NonNull String documentId) {
        return new SupplierOrderStatusResult(documentId, Status.NOT_FOUND, null, null, null, null, List.of());
    }

    /**
     * Whether the vendor acknowledges holding this document at all.
     *
     * <p>Deliberately <em>not</em> called "terminal". {@link Status#CONFIRMED} is terminal for
     * the <em>transmission</em> — the create succeeded and will never be re-sent — but it is the
     * beginning, not the end, of the fulfilment story this feed exists to report. Polling that
     * stopped at CONFIRMED would deliver a purchase-order timeline with no delivery dates and no
     * despatches on it, which is precisely the gap #1318 was filed to close. When fulfilment is
     * finished is decided by {@code OrderStatusCompletion}, over the schedules and despatches.
     */
    public boolean isKnownToVendor() {
        return status != Status.NOT_FOUND;
    }
}
