package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.CreateAdjustmentRequestDto;
import com.positivity.inventory.internal.dto.RecordMovementRequest;
import com.positivity.inventory.internal.dto.AdjustmentRequestResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Service for recording stock movements in the inventory ledger.
 *
 * <p>
 * All ledger entries are immutable once created (append-only).
 * Adjustments follow a two-step create+approve workflow.
 *
 * Issue: CAP-215 Story #37
 */
public interface StockMovementService {

    /**
     * Records a stock movement and creates the corresponding ledger entry.
     *
     * <p>
     * TRANSFER movements create two entries: TRANSFER_OUT at source, TRANSFER_IN at
     * destination.
     *
     * @param request     the movement details
     * @param actorUserId the authenticated user recording this movement
     * @return the primary ledger entry created
     * @throws com.positivity.inventory.internal.exception.ProductNotFoundException   if
     *                                                                                productSku
     *                                                                                not
     *                                                                                found
     * @throws com.positivity.inventory.internal.exception.LocationNotFoundException  if
     *                                                                                locationId
     *                                                                                not
     *                                                                                found
     * @throws com.positivity.inventory.internal.exception.InsufficientStockException if
     *                                                                                PICK/ISSUE
     *                                                                                exceeds
     *                                                                                on-hand
     */
    @NonNull
    InventoryLedgerEntry recordMovement(@NonNull RecordMovementRequest request, @NonNull String actorUserId);

    /**
     * Creates a draft adjustment request pending approval.
     *
     * @param request     the adjustment details (reasonCode is mandatory)
     * @param actorUserId the authenticated user creating this request
     * @return the created adjustment request response payload
     */
    @NonNull
    AdjustmentRequestResponse createAdjustmentRequest(@NonNull CreateAdjustmentRequestDto request,
            @NonNull String actorUserId);

    /**
     * Approves and posts an adjustment request to the ledger.
     *
     * @param adjustmentRequestId the ID of the pending adjustment request
     * @param approverUserId      the authenticated user approving this request
     * @return the created ledger entry
     */
    @NonNull
    InventoryLedgerEntry approveAdjustmentRequest(@NonNull UUID adjustmentRequestId, @NonNull String approverUserId);
}
