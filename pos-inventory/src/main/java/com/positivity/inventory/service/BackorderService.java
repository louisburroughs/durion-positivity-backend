package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.backorder.BackorderResponse;
import com.positivity.inventory.internal.enums.BackorderStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Backorder lifecycle (odoo-parity G1, issue #1046).
 *
 * <p>Opens backorders for demand that could not be met and resolves them oldest-first as
 * availability arrives. Opening posts the ATP-neutral {@code BACKORDER_CREATED} ledger entry and a
 * {@code BackorderCreatedV1} fact; resolution posts {@code BACKORDER_RESOLVED} and a
 * {@code BackorderResolvedV1} fact. Auto-resolution is triggered internally by inbound on-hand
 * postings (see the ledger posting funnel) — this interface exposes the direct creation path that
 * the reservation/pick flows and the G2 shortage resolver call.
 */
public interface BackorderService {

    /**
     * Opens a backorder for a workorder line whose demand was short (the resolver's BACKORDER
     * choice). Idempotent per {@code (workorderLineId, sku)}: if an OPEN backorder already exists it
     * is returned unchanged rather than duplicated.
     *
     * @param workorderLineId workorder line whose demand was short
     * @param sku stock-item identifier that was short (ledger stock-item string)
     * @param quantityShort quantity that could not be fulfilled (must be positive)
     * @param locationId site the demand is short at; matched by the auto-resolution trigger
     * @return the OPEN backorder (existing or newly created)
     */
    @NonNull
    BackorderResponse createBackorder(
            @NonNull UUID workorderLineId, @NonNull String sku, int quantityShort, @Nullable UUID locationId);

    /**
     * Opens a backorder for a sales-order line whose demand was short (CAP #1315), the sales-order
     * checkout gate's admit-rather-than-reject path. Idempotent per {@code (salesOrderLineId, sku)},
     * mirroring {@link #createBackorder}.
     *
     * @param salesOrderLineId sales-order line whose demand was short
     * @param sku stock-item identifier that was short (ledger stock-item string)
     * @param quantityShort quantity that could not be fulfilled (must be positive)
     * @param locationId site the demand is short at; matched by the auto-resolution trigger
     * @return the OPEN backorder (existing or newly created)
     */
    @NonNull
    BackorderResponse createBackorderForSalesOrderLine(
            @NonNull UUID salesOrderLineId, @NonNull String sku, int quantityShort, @Nullable UUID locationId);

    /**
     * Retrieves one backorder.
     *
     * @param backorderId the backorder id
     * @return the backorder
     */
    @NonNull
    BackorderResponse getBackorder(@NonNull UUID backorderId);

    /**
     * Lists backorders matching the optional filters, newest first.
     *
     * @param status optional lifecycle-status filter
     * @param sku optional SKU filter
     * @param locationId optional site filter
     * @param workorderLineId optional workorder-line filter
     * @param salesOrderLineId optional sales-order-line filter (CAP #1315)
     * @return matching backorders
     */
    @NonNull
    List<BackorderResponse> listBackorders(
            @Nullable BackorderStatus status,
            @Nullable String sku,
            @Nullable UUID locationId,
            @Nullable UUID workorderLineId,
            @Nullable UUID salesOrderLineId);
}
