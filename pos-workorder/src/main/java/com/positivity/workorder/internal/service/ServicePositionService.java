package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.AssignServicePositionRequest;
import com.positivity.workorder.internal.dto.ServicePositionResponse;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.ResourceType;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Assign, change and release the service position a workorder occupies (#1983, #1984).
 *
 * <p>Symmetric with {@link TechnicianAssignmentService} by design: <em>where</em> the work happens
 * and <em>who</em> does it are independent assignments of the same workorder, each assignable,
 * changeable and releasable across the open lifecycle, and each keeping its own append-only
 * history. Changing one never changes the other.
 */
public interface ServicePositionService {

    /**
     * Place the workorder on a position, or move it to a different one.
     *
     * <p>Idempotent for a repeat of the placement already in force: the same position is not
     * released and re-taken, and no new history row appears.
     *
     * @throws com.positivity.workorder.internal.exception.WorkorderNotFoundException no such workorder
     * @throws com.positivity.workorder.internal.exception.WorkorderClosedException the workorder is closed
     * @throws com.positivity.workorder.internal.exception.ServicePositionInvalidException unknown position,
     *     or one at another site
     * @throws com.positivity.workorder.internal.exception.ServicePositionOccupiedException an exclusive
     *     position already holds another open workorder
     */
    @NonNull
    ServicePositionResponse assignPosition(
            @NonNull UUID workorderId, @NonNull AssignServicePositionRequest request, @NonNull String actor);

    /**
     * Give up the workorder's position, leaving it unplaced.
     *
     * <p>Idempotent: releasing a workorder that holds no position succeeds and writes nothing.
     */
    @NonNull
    ServicePositionResponse releasePosition(@NonNull UUID workorderId, @NonNull String actor, @Nullable String reason);

    /** The workorder's current position and technician, with the full position history. */
    @NonNull
    ServicePositionResponse getPosition(@NonNull UUID workorderId);

    /**
     * Free the position a workorder holds because its lifecycle ended (#1984).
     *
     * <p>Called from {@link WorkorderStateMachine} on the transition to COMPLETED or CANCELLED, not
     * by a client: completion is what releases a bay in practice, and leaving the claim behind would
     * black out a position nobody is in. The history row is closed rather than deleted, so the
     * record of where the job was done survives the release.
     *
     * <p>Unlike {@link #releasePosition} this runs no status check — it <em>is</em> the status
     * change — and never throws for a workorder that holds nothing.
     */
    void releaseOnClose(@NonNull UUID workorderId, @NonNull String actor, @NonNull String reason);

    /**
     * Apply a position change that some other path has already decided on, so that path still gets
     * occupancy enforcement and a history row (#1984).
     *
     * <p>Two callers, both of which wrote {@code resourceId} straight onto the workorder before this
     * existed: {@code overrideOperationalContext}, the manager exception path, and the inbound
     * {@code AssignmentUpdated} event from pos-shop-manager. Left alone, either could put a second
     * open workorder on a bay — which is now a unique-index violation surfacing as a 500 from a REST
     * call and as a swallowed listener failure on the event path — and neither would leave a trace in
     * the position history that {@code GET .../position} reads.
     *
     * <p>Deliberately does <em>not</em> validate the position against the {@code ext_bay} /
     * {@code ext_mobile_unit} replicas the way {@link #assignPosition} does. An override is an
     * override: a manager re-slotting a job must not be blocked because a replica row has not landed
     * yet, and the event path's producer is the system of record for the resource it names. What is
     * not negotiable either way is the one-open-workorder rule, because the database holds it.
     *
     * <p>Records and refuses only — it does not touch {@code resourceId} / {@code resourceType} on
     * the entity and does not save. The caller writes the fields it already writes today, keeps its
     * own audit trail, and owns the save and the fact publication; this adds the two things every
     * path needs and none of them had. Call it before writing the fields: it reads the history to
     * decide whether anything actually changed.
     *
     * @param workorder    the workorder being moved, read for its id and site
     * @param resourceType the kind of position, or {@code null} together with a null id to unplace
     * @param resourceId   the position, or {@code null} to unplace
     * @param actor        who is making the change, for the history row
     * @param reason       why, when known
     * @throws com.positivity.workorder.internal.exception.ServicePositionOccupiedException an exclusive
     *     position already holds another open workorder
     */
    void recordPositionChange(
            @NonNull Workorder workorder,
            @Nullable ResourceType resourceType,
            @Nullable UUID resourceId,
            @NonNull String actor,
            @Nullable String reason);
}
