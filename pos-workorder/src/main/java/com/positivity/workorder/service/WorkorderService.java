package com.positivity.workorder.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.event.EstimateRevisedEvent;
import com.positivity.workorder.internal.event.WorkCompletedEvent;
import com.positivity.workorder.internal.service.WorkorderStateMachine;

public interface WorkorderService {

        record ReopenResult(UUID workorderId, String currentStatus, Boolean isReopened, Instant reopenedAt) {
        }

        List<Workorder> getAllWorkorders();

        Optional<Workorder> getWorkorderById(UUID id);

        Workorder createWorkorder(UUID estimateId, UUID customerId);

        /**
         * Create a workorder with idempotency key support.
         * 
         * <p>
         * If an idempotency key is provided and has been processed before,
         * returns the existing workorder instead of creating a duplicate.
         * </p>
         * 
         * @param estimateId     the estimate ID
         * @param customerId     the customer ID
         * @param idempotencyKey optional idempotency key for duplicate prevention; if
         *                       null, idempotency is not enforced
         * @return the created or existing workorder
         */
        Workorder createWorkorderWithIdempotency(UUID estimateId, UUID customerId, String idempotencyKey);

        Workorder createWorkorder(Workorder workorder);

        void deleteWorkorder(UUID id);

        void startWorkorder(UUID workorderId, UUID userId, String reason);

        Workorder approveWorkorder(UUID workorderId, UUID customerId, String signatureData,
                        String signatureMimeType, String signerName, String notes);

        void transitionWorkorder(UUID workorderId, WorkorderStatus toStatus, UUID userId, String reason);

        List<com.positivity.workorder.internal.entity.WorkorderStateTransition> getTransitionHistory(
                        UUID workorderId);

        List<com.positivity.workorder.internal.entity.WorkorderSnapshot> getSnapshotHistory(UUID workorderId);

        WorkorderStateMachine.CompletionPreconditions getCompletionPreconditions(UUID workorderId);

        String getCurrentWorkorderStatus(UUID workorderId);

        Instant getCompletedAt(UUID workorderId);

        WorkCompletedEvent completeWorkorder(UUID workorderId, UUID userId, String completionNotes);

        ReopenResult reopenCompletedWorkorder(UUID workorderId, UUID userId, String reopenReason);

        /**
         * Listen for EstimateRevisedEvent and invalidate Workorder approval if needed.
         * This implements the automatic approval invalidation workflow when estimates
         * are financially revised.
         * 
         * @param event the EstimateRevisedEvent containing revision details
         */
        void onEstimateRevised(EstimateRevisedEvent event);

}
