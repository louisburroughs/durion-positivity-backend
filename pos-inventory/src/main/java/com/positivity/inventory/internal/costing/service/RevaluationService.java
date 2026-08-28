package com.positivity.inventory.internal.costing.service;

import com.positivity.inventory.internal.dto.revaluation.CreateRevaluationRequest;
import com.positivity.inventory.internal.dto.revaluation.RejectRevaluationRequest;
import com.positivity.inventory.internal.dto.revaluation.RevaluationResponse;
import com.positivity.inventory.internal.enums.RevaluationStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Manual cost-revaluation workflow (odoo-parity J4, issue #1054).
 *
 * <p>An operator corrects a SKU's standard price (STANDARD method) or running average (AVERAGE) via
 * an approval-gated document. The value delta (|Δunit-cost| × on-hand) gates the approval tier: a
 * below-threshold revaluation applies immediately, an above-threshold one waits for approval. On
 * application the SKU cost state is restated and a {@code ProductValueChangedV1} fact is emitted.
 */
public interface RevaluationService {

    /**
     * Submits a revaluation. Below the value threshold it is auto-applied (cost state restated and
     * the fact emitted) in the same transaction; above it (or an unknown value) it enters
     * {@code PENDING_APPROVAL} with no cost change.
     *
     * @param request the revaluation request
     * @return the created revaluation with its resulting status
     */
    @NonNull
    RevaluationResponse createRevaluation(@NonNull CreateRevaluationRequest request);

    /**
     * Approves a pending revaluation, restating the SKU cost state and emitting the fact.
     *
     * @param revaluationId the revaluation document id
     * @return the applied revaluation
     */
    @NonNull
    RevaluationResponse approveRevaluation(@NonNull UUID revaluationId);

    /**
     * Rejects a pending revaluation; the SKU cost state is untouched.
     *
     * @param revaluationId the revaluation document id
     * @param request the rejection request with reason
     * @return the rejected revaluation
     */
    @NonNull
    RevaluationResponse rejectRevaluation(@NonNull UUID revaluationId, @NonNull RejectRevaluationRequest request);

    /**
     * Retrieves one revaluation document.
     *
     * @param revaluationId the revaluation document id
     * @return the revaluation
     */
    @NonNull
    RevaluationResponse getRevaluation(@NonNull UUID revaluationId);

    /**
     * Lists revaluation documents matching the optional filters, newest first.
     *
     * @param stockItemId optional SKU filter
     * @param status optional status filter
     * @return matching revaluations
     */
    @NonNull
    List<RevaluationResponse> listRevaluations(@Nullable String stockItemId, @Nullable RevaluationStatus status);
}
