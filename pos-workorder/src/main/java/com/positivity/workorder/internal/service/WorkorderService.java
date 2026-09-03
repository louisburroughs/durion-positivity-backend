package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.AssignmentUpdatedEvent;
import com.positivity.workorder.internal.dto.OperationalContextOverrideRequest;
import com.positivity.workorder.internal.dto.OperationalContextResponse;
import com.positivity.workorder.internal.dto.WorkorderItemCompletionResponse;
import com.positivity.workorder.internal.dto.WorkorderResponse;
import com.positivity.workorder.internal.dto.WorkorderSnapshotResponse;
import com.positivity.workorder.internal.dto.WorkorderStartResponse;
import com.positivity.workorder.internal.dto.WorkorderStateTransitionResponse;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.event.EstimateRevisedEvent;
import com.positivity.workorder.internal.event.WorkCompletedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface WorkorderService {

    record ReopenResult(UUID workorderId, String currentStatus, Boolean isReopened, Instant reopenedAt) {}

    List<WorkorderResponse> getAllWorkorders();

    Optional<WorkorderResponse> getWorkorderById(UUID id);

    WorkorderResponse createWorkorder(UUID estimateId, UUID customerId);

    /**
     * Mark a single workorder service line as COMPLETED. Allowed from active item states
     * (OPEN / READY_TO_EXECUTE / IN_PROGRESS); rejected for CANCELLED or PENDING_APPROVAL.
     * Completing an already-COMPLETED item is idempotent.
     *
     * @param workorderId   owning workorder
     * @param serviceLineId service line to complete
     * @param actorId       acting user
     * @return completion result with the resulting item status
     */
    @NonNull
    WorkorderItemCompletionResponse completeServiceItem(
            @NonNull UUID workorderId, @NonNull UUID serviceLineId, @NonNull String actorId);

    /**
     * Mark a single workorder part as COMPLETED. Same transition rules as
     * {@link #completeServiceItem}.
     *
     * @param workorderId owning workorder
     * @param partId      part to complete
     * @param actorId     acting user
     * @return completion result with the resulting item status
     */
    @NonNull
    WorkorderItemCompletionResponse completePartItem(
            @NonNull UUID workorderId, @NonNull UUID partId, @NonNull String actorId);

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
    WorkorderResponse createWorkorderWithIdempotency(UUID estimateId, UUID customerId, String idempotencyKey);

    /**
     * Create a workorder from a caller-assembled entity, honoring whatever fields (estimateId,
     * approvalId, status, ...) the caller has already set on it.
     *
     * <p>Unlike the other create methods, this one is a purely internal collaborator: no
     * controller or other module calls it today, so it is left operating on the managed
     * {@link Workorder} entity rather than a DTO (issue #1550).
     */
    Workorder createWorkorder(Workorder workorder);

    void deleteWorkorder(UUID id);

    void startWorkorder(UUID workorderId, String actorId, String reason);

    WorkorderResponse approveWorkorder(
            UUID workorderId,
            UUID customerId,
            String signatureData,
            String signatureMimeType,
            String signerName,
            String notes);

    void transitionWorkorder(UUID workorderId, WorkorderStatus toStatus, String actorId, String reason);

    List<WorkorderStateTransitionResponse> getTransitionHistory(UUID workorderId);

    List<WorkorderSnapshotResponse> getSnapshotHistory(UUID workorderId);

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
     * Updates assignment context (locationId, resourceId, resourceType, mechanicIds) on a
     * workorder
     * from an AssignmentUpdated event. Uses full-replace semantics.
     * Every workorder that is not locked is updatable — {@link Workorder#isLocked()}, i.e. CANCELLED
     * or COMPLETED-and-not-reopened, is the only refusal. CAP:140 Story #64; resourceType and the
     * widened guard added by #1656.
     *
     * <p>The guard used to be {@code status ∈ {DRAFT, APPROVED, ASSIGNED}}, which silently dropped
     * the mid-day reassignment #1656 requires: a job moved between resources while it is running is
     * WORK_IN_PROGRESS by definition, so the old resource was never released and the new one was
     * never held. A locked workorder is still refused — a cancelled or completed job must not accept
     * a reassignment.
     *
     * <p>An event that omits {@code resourceType} is applied as
     * {@link com.positivity.workorder.internal.enums.ResourceType#BAY} — see
     * {@code AssignmentUpdatedEvent#resolveResourceType()} for why.
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
     *
     * <p>The override replaces resource id and {@code resourceType} together (#1656), the same
     * full-replace the assignment-event path performs, so a bay-to-mobile-unit re-slot can never
     * leave the workorder pointing at one kind of resource while typed as the other.
     */
    @NonNull
    OperationalContextResponse overrideOperationalContext(
            @NonNull UUID workorderId, @NonNull OperationalContextOverrideRequest override);

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
