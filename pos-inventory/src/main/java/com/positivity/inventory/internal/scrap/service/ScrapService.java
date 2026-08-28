package com.positivity.inventory.internal.scrap.service;

import com.positivity.inventory.internal.dto.scrap.ApproveScrapRequest;
import com.positivity.inventory.internal.dto.scrap.CreateScrapRequest;
import com.positivity.inventory.internal.dto.scrap.RejectScrapRequest;
import com.positivity.inventory.internal.dto.scrap.ScrapResponse;
import com.positivity.inventory.internal.enums.ScrapReasonCode;
import com.positivity.inventory.internal.enums.ScrapStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Scrap/write-off workflow (odoo-parity D1, issue #1030).
 *
 * <p>Creates scrap documents, evaluates them against the scrap flow's value-based approval
 * thresholds (quantity × cost snapshot), and posts approved scraps to the inventory ledger as
 * {@code SCRAP_OUT} through the single posting funnel.
 */
public interface ScrapService {

    /**
     * Creates a scrap document. Below-threshold scraps are auto-approved and posted in the same
     * transaction; above-threshold (or unknown-value) scraps enter {@code PENDING_APPROVAL}.
     *
     * @param request the scrap creation request
     * @return the created scrap with its resulting status
     */
    @NonNull
    ScrapResponse createScrap(@NonNull CreateScrapRequest request);

    /**
     * Approves a pending scrap and posts it to the ledger.
     *
     * @param scrapId the scrap document id
     * @param request the approval request (optional negative-stock override)
     * @return the approved and posted scrap
     */
    @NonNull
    ScrapResponse approveScrap(@NonNull UUID scrapId, @NonNull ApproveScrapRequest request);

    /**
     * Rejects a pending scrap; stock is untouched.
     *
     * @param scrapId the scrap document id
     * @param request the rejection request with reason
     * @return the rejected scrap
     */
    @NonNull
    ScrapResponse rejectScrap(@NonNull UUID scrapId, @NonNull RejectScrapRequest request);

    /**
     * Retrieves one scrap document.
     *
     * @param scrapId the scrap document id
     * @return the scrap
     */
    @NonNull
    ScrapResponse getScrap(@NonNull UUID scrapId);

    /**
     * Lists scrap documents matching the optional filters, newest first.
     *
     * @param reasonCode optional reason filter
     * @param status optional status filter
     * @param locationId optional location filter
     * @param createdFrom optional inclusive lower bound on creation time
     * @param createdTo optional inclusive upper bound on creation time
     * @return matching scraps
     */
    @NonNull
    List<ScrapResponse> listScraps(
            @Nullable ScrapReasonCode reasonCode,
            @Nullable ScrapStatus status,
            @Nullable UUID locationId,
            @Nullable Instant createdFrom,
            @Nullable Instant createdTo);
}
