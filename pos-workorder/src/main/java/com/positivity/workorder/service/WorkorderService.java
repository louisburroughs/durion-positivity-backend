package com.positivity.workorder.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;

import com.positivity.workorder.internal.dto.AssignmentUpdatedEvent;
import com.positivity.workorder.internal.dto.OperationalContextOverrideRequest;
import com.positivity.workorder.internal.dto.OperationalContextResponse;
import com.positivity.workorder.internal.dto.WorkorderStartResponse;
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

        void startWorkorder(UUID workorderId, String actorId, String reason);

        Workorder approveWorkorder(UUID workorderId, UUID customerId, String signatureData,
                        String signatureMimeType, String signerName, String notes);

        void transitionWorkorder(UUID workorderId, WorkorderStatus toStatus, String actorId, String reason);

        List<com.positivity.workorder.internal.entity.WorkorderStateTransition> getTransitionHistory(
                        UUID workorderId);

        List<com.positivity.workorder.internal.entity.WorkorderSnapshot> getSnapshotHistory(UUID workorderId);

        WorkorderStateMachine.CompletionPreconditions getCompletionPreconditions(UUID workorderId);

        String getCurrentWorkorderStatus(UUID workorderId);

        Instant getCompletedAt(UUID workorderId);

        WorkCompletedEvent completeWorkorder(UUID workorderId, String actorId, String completionNotes);

        ReopenResult reopenCompletedWorkorder(UUID workorderId, String actorId, String reopenReason);

        /**
         * Listen for EstimateRevisedEvent and invalidate Workorder approval if needed.
         * This implements the automatic approval invalidation workflow when estimates
         * are financially revised.
         * 
         * @param event the EstimateRevisedEvent containing revision details
         */
        void onEstimateRevised(EstimateRevisedEvent event);

        /**
         * Updates assignment context (locationId, resourceId, mechanicIds) on a
         * workorder
         * from an AssignmentUpdated event. Uses full-replace semantics.
         * Only pre-execution states (DRAFT, APPROVED, ASSIGNED) are updatable.
         * CAP:140 Story #64.
         *
         * @param event the assignment updated event from shop management service
         */
        void handleAssignmentUpdated(@NonNull AssignmentUpdatedEvent event);

        /**
         * Returns the operational context for the given workorder, fetched from Shopmgr
         * (read-only).
         * Context is locked (read-only, no override possible) once workStartedAt is
         * set.
         * CAP:140 Story #59.
         */
        @NonNull
        OperationalContextResponse getOperationalContext(@NonNull UUID workorderId);

        /**
         * Manager override: replaces the operational context for a workorder.
         * Not allowed after workStartedAt is set.
         * CAP:140 Story #59.
         */
        @NonNull
        OperationalContextResponse overrideOperationalContext(@NonNull UUID workorderId,
                        @NonNull OperationalContextOverrideRequest override);

        /**
         * Starts work on a workorder: records operationalContextVersion and
         * workStartedAt.
         * After this call, the operational context is locked.
         * CAP:140 Story #59.
         */
        @NonNull
        WorkorderStartResponse startWork(@NonNull UUID workorderId);

        @NonNull
        WorkorderStartResponse startWork(@NonNull UUID workorderId, String requestedUserId, String reason);

}
