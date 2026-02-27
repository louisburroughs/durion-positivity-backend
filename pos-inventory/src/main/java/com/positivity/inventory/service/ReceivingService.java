package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.receiving.CrossDockRequest;
import com.positivity.inventory.internal.dto.receiving.CrossDockResponse;
import com.positivity.inventory.internal.dto.receiving.CreateReceivingSessionRequest;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsRequest;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsResponse;
import com.positivity.inventory.internal.dto.receiving.ReceivingSessionResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service for managing inventory receiving sessions.
 *
 * <p>
 * Supports creation of receiving sessions from Purchase Orders (PO)
 * or Advanced Shipping Notices (ASN). Blind receiving is explicitly NOT
 * supported.
 *
 * Issue: CAP-216 Stories #35, #34, #33
 */
public interface ReceivingService {

    /**
     * Creates a new receiving session from a PO or ASN identifier.
     *
     * @param request     the source document identifier and entry method
     * @param actorUserId the authenticated user initiating the session
     * @return the created receiving session with pre-populated lines
     * @throws com.positivity.inventory.internal.exception.SourceDocumentNotFoundException        if
     *                                                                                            PO/ASN
     *                                                                                            not
     *                                                                                            found
     * @throws com.positivity.inventory.internal.exception.SourceDocumentAlreadyReceivedException if
     *                                                                                            PO/ASN
     *                                                                                            already
     *                                                                                            closed
     */
    @NonNull
    ReceivingSessionResponse createReceivingSession(
            @NonNull CreateReceivingSessionRequest request,
            @NonNull String actorUserId);

    /**
     * Retrieves a receiving session by ID.
     *
     * @param sessionId the session ID
     * @return the receiving session
     * @throws com.positivity.inventory.internal.exception.ReceivingSessionNotFoundException if
     *                                                                                       not
     *                                                                                       found
     */
    @NonNull
    ReceivingSessionResponse getReceivingSession(@NonNull UUID sessionId);

    /**
     * Records actual received quantities for lines in a receiving session.
     * Generates InventoryLedgerEntry records (GOODS_RECEIPT) for each line.
     * Creates InventoryVariance records for lines with discrepancies.
     *
     * @param sessionId   the receiving session ID
     * @param request     the received quantities for each line
     * @param actorUserId the authenticated user performing the receipt
     * @return summary of processed lines and any variances
     * @throws com.positivity.inventory.internal.exception.ReceivingSessionNotFoundException if
     *                                                                                       session
     *                                                                                       not
     *                                                                                       found
     */
    @NonNull
    ReceiveItemsResponse receiveItemsIntoStaging(
            @NonNull UUID sessionId,
            @NonNull ReceiveItemsRequest request,
            @NonNull String actorUserId);

    /**
     * Cross-docks a receiving line directly to a workorder.
     * Creates GOODS_RECEIVED + GOODS_ISSUE ledger entries atomically.
     *
     * AC-1: Full qty cross-dock
     * AC-2: Partial qty cross-dock
     * AC-3: Closed workorder → throws WorkorderClosedException
     * AC-4: Part mismatch without inventory:override:part-match → throws
     * PartMatchPermissionException
     *
     * @param sessionId   the receiving session ID
     * @param lineId      line ID to cross-dock
     * @param request     cross-dock details (workorderId, qty)
     * @param actorUserId the authenticated user performing the cross-dock
     * @return cross-dock result with linked workorder and ledger evidence
     */
    @NonNull
    CrossDockResponse crossDockLineToWorkorder(
            @NonNull UUID sessionId,
            @NonNull UUID lineId,
            @NonNull CrossDockRequest request,
            @NonNull String actorUserId);
}
