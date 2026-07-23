package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.transfer.CreateTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.TransferOrderResponse;
import com.positivity.inventory.internal.enums.TransferOrderStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Cross-site transfer order lifecycle (odoo-parity C1, issue #1035; spec §4).
 *
 * <p>Status machine: {@code DRAFT → APPROVED(optional) → DISPATCHED → PARTIALLY_RECEIVED →
 * RECEIVED | SHORT_CLOSED | CANCELLED}. The approval step exists only when
 * {@code pos.inventory.transfer.approval-required=true} (decision D-8, default off). Both ends
 * of every transfer are validated against the {@code LocationRef} roster per
 * DECISION-INVENTORY-009 (ACTIVE only). Dispatch/receive ledger posting is parity-C2;
 * short-close is parity-C3.
 */
public interface TransferOrderService {

    /**
     * Creates a transfer order in DRAFT.
     *
     * <p>Validations: source and destination must be different sites, both known in the
     * {@code LocationRef} roster and ACTIVE; optional storage locations must belong to their
     * respective sites when the {@code ext_storage_location} replica can resolve them.
     *
     * @param request the creation request
     * @return the created order
     */
    @NonNull
    TransferOrderResponse createTransferOrder(@NonNull CreateTransferOrderRequest request);

    /**
     * Retrieves one transfer order with its lines.
     *
     * @param transferOrderId the order id
     * @return the order
     */
    @NonNull
    TransferOrderResponse getTransferOrder(@NonNull UUID transferOrderId);

    /**
     * Lists transfer orders matching the optional filters, newest first.
     *
     * @param status optional lifecycle status filter
     * @param sourceLocationId optional source site filter
     * @param destinationLocationId optional destination site filter
     * @return matching orders
     */
    @NonNull
    List<TransferOrderResponse> listTransferOrders(
            @Nullable TransferOrderStatus status,
            @Nullable UUID sourceLocationId,
            @Nullable UUID destinationLocationId);

    /**
     * Approves a DRAFT order (DRAFT → APPROVED). Only available when the
     * {@code pos.inventory.transfer.approval-required} flag is on; a 409 conflict otherwise —
     * with the flag off, DRAFT orders dispatch directly (decision D-8).
     *
     * @param transferOrderId the order id
     * @return the approved order
     */
    @NonNull
    TransferOrderResponse approveTransferOrder(@NonNull UUID transferOrderId);

    /**
     * Cancels an order before dispatch (DRAFT/APPROVED → CANCELLED). Once dispatched, stock has
     * physically moved and cancellation is a 409 conflict — discrepancies resolve via
     * short-close (parity-C3).
     *
     * @param transferOrderId the order id
     * @return the cancelled order
     */
    @NonNull
    TransferOrderResponse cancelTransferOrder(@NonNull UUID transferOrderId);
}
