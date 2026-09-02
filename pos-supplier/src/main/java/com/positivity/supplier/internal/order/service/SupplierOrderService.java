package com.positivity.supplier.internal.order.service;

import com.positivity.supplier.internal.order.service.model.OrderTransmissionStatus;
import com.positivity.supplier.internal.order.service.model.TransmissionResolutionRequest;
import com.positivity.supplier.internal.service.model.PagedResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read and resolution surface over purchase-order <em>transmission</em> state (ADR-0026
 * contract, ADR-0049 §2, ADR-0052 §4).
 *
 * <p>pos-supplier holds transmission/exchange state only — the purchase-order aggregate stays
 * with the ordering domain, and the order lifecycle is event-driven per ADR-0049 §3
 * ({@code supplier.order.requested} in; {@code supplier.order.confirmed} /
 * {@code supplier.order.rejected} / {@code supplier.orderstatus.changed} out). This interface
 * serves the module's own frontend-facing controllers. It is <strong>not</strong> a synchronous
 * cross-module surface: ADR-0049 §4 allows exactly one of those, and it is live stock inquiry.
 *
 * <p>There is no "transmit" and no "re-send" operation here, and there never will be. Ordering is
 * something the ordering domain asks for by event; re-sending is the duplicate-order risk
 * ADR-0052 exists to prevent.
 */
public interface SupplierOrderService {

    /**
     * Returns the transmission states recorded for a purchase order, one per transmission
     * intent (initial, revision, replacement, split — ADR-0052 §1).
     *
     * @param purchaseOrderId originating purchase-order aggregate id
     * @return transmission states, newest intent first; empty when nothing was ever transmitted
     */
    @NonNull
    List<OrderTransmissionStatus> findTransmissionsByPurchaseOrderId(@NonNull UUID purchaseOrderId);

    /**
     * Searches the whole transmission ledger across purchase orders (issue #1638 decision 6) —
     * the queue view an operator works, above all filtered to {@code MANUAL_REVIEW}.
     *
     * <p>Every filter is optional and they compose conjunctively. The window binds against the
     * intent's {@code createdAt} (when the order entered the vendor queue), half-open —
     * {@code createdFrom} inclusive, {@code createdTo} exclusive — and results come back newest
     * first on that same timestamp.
     *
     * @param attemptState only transmissions currently in this state, when given
     * @param vendorProfileId only transmissions to this vendor profile, when given
     * @param search case-insensitive contains-match against the purchase-order number and the
     *     vendor's own order number; blank is treated as absent
     * @param createdFrom window start on {@code createdAt}, inclusive, when given
     * @param createdTo window end on {@code createdAt}, exclusive, when given
     * @param page zero-based page index
     * @param size page size
     * @return one page of matching transmissions, newest intent first
     */
    @NonNull
    PagedResponse<OrderTransmissionStatus> searchTransmissions(
            OrderTransmissionStatus.@Nullable State attemptState,
            @Nullable UUID vendorProfileId,
            @Nullable String search,
            @Nullable Instant createdFrom,
            @Nullable Instant createdTo,
            int page,
            int size);

    /**
     * Returns one transmission by its intent id.
     *
     * @param transmissionIntentId the intent to read
     * @return its current transmission state
     */
    @NonNull
    OrderTransmissionStatus getTransmission(@NonNull UUID transmissionIntentId);

    /**
     * Applies an operator's resolution to a transmission awaiting manual review (ADR-0052 §4).
     *
     * <p>Permitted only from {@code MANUAL_REVIEW}: every other state either has a definite
     * outcome already or is still being worked on automatically, and overriding those by hand
     * would let an operator contradict a vendor's own answer.
     *
     * @param transmissionIntentId the transmission to resolve
     * @param request the action and the operator's evidence for it
     * @return the transmission's state after resolution
     */
    @NonNull
    OrderTransmissionStatus resolveTransmission(
            @NonNull UUID transmissionIntentId, @NonNull TransmissionResolutionRequest request);
}
