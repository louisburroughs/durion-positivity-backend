package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.TechnicianAssignmentRecord;
import com.positivity.workorder.internal.entity.ExtMobileUnitReplica;
import com.positivity.workorder.internal.entity.TechnicianAssignment;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.ResourceType;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.TechnicianAlreadyAssignedException;
import com.positivity.workorder.internal.exception.TechnicianNotAssignedException;
import com.positivity.workorder.internal.exception.TechnicianNotFoundException;
import com.positivity.workorder.internal.exception.TechnicianNotStaffedAtSiteException;
import com.positivity.workorder.internal.exception.WorkorderClosedException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPersonReplicaRepository;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing technician assignments to workorders.
 *
 * <p>
 * Business rules:
 * <ul>
 * <li>Assignment is allowed only from APPROVED, ASSIGNED, or WORK_IN_PROGRESS
 * status</li>
 * <li>ASSIGNED means the workorder is ready to be worked: a current technician
 * <em>and</em> a bay or mobile unit. Every assign, reassign and release hands the
 * status decision to {@code WorkorderStateMachine.reconcileAssigned}, so a workorder
 * with nobody on it falls back to APPROVED and one with nowhere to be worked never
 * reaches ASSIGNED (#2010, #2011)</li>
 * <li>A workorder has at most one current technician. Assign requires none, reassign
 * requires one, release gives the current one up; the partial unique index
 * {@code technician_assignment_one_current_uniq} guarantees the rule under
 * concurrency rather than leaving it to these checks (#1985)</li>
 * <li>Reassignment marks previous assignment as not current</li>
 * <li>Assignment history is append-only and ordered newest-first; closed rows are
 * never deleted</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TechnicianAssignmentServiceImpl implements TechnicianAssignmentService {
    private final Clock clock;

    private final TechnicianAssignmentRepository assignmentRepository;
    private final WorkorderRepository workorderRepository;
    private final WorkorderStateMachine stateMachine;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;
    private final PeopleAvailabilityLocalService peopleAvailabilityLocalService;
    private final ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository;

    /**
     * Assign a technician to a workorder.
     *
     * <p>
     * An APPROVED workorder becomes ASSIGNED only if it also stands on a bay or a mobile
     * unit (#2011); with nowhere to be worked it stays APPROVED until it is placed.
     * The workorder must have no current technician: changing hands is
     * {@link #reassignTechnician}, which records a reason.
     *
     * @param workorderId  the workorder ID
     * @param technicianId the technician ID to assign
     * @param assignedBy   the user ID performing the assignment
     * @param notes        optional notes for the assignment
     * @return the created assignment record
     * @throws WorkorderNotFoundException if workorder not found
     * @throws TechnicianAlreadyAssignedException if a current technician already holds the workorder
     * @throws IllegalStateException  if workorder status doesn't allow assignment
     */
    @Transactional
    @NonNull
    public TechnicianAssignmentRecord assignTechnician(
            @NonNull UUID workorderId, @NonNull UUID technicianId, @NonNull String assignedBy, @Nullable String notes) {

        Workorder workorder = workorderRepository
                .findById(workorderId)
                .orElseThrow(() -> new WorkorderNotFoundException(workorderId));

        // Validate status
        validateAssignmentAllowed(workorder);

        // Assign means "this workorder has no technician yet" (#1985). It used to silently retire
        // whoever held the job and install the new one, which made an accidental double assign
        // indistinguishable from a deliberate hand-over — the first technician's row was closed with
        // a canned reason and nobody was told. Changing hands is reassignTechnician, which takes a
        // reason; here a held workorder is a 409 naming its current technician.
        requireKnownTechnician(technicianId);

        Optional<TechnicianAssignment> existingAssignment =
                assignmentRepository.findByWorkorder_IdAndCurrentTrue(workorderId);

        if (existingAssignment.isPresent()) {
            UUID currentTechnicianId = existingAssignment.get().getTechnicianId();
            throw new TechnicianAlreadyAssignedException(
                    "Workorder " + workorderId + " is already assigned to technician " + currentTechnicianId,
                    currentTechnicianId);
        }

        // The assign-vs-reassign conflict above is authoritative over staffing: a second technician
        // on an already-held workorder is always 409 TECHNICIAN_ALREADY_ASSIGNED, even when the
        // replacement is staffed only elsewhere.
        requireStaffedAtSite(technicianId, workorder);

        // Create new assignment
        LocalDateTime now = LocalDateTime.now(clock);
        TechnicianAssignment assignment = TechnicianAssignment.builder()
                .workorder(new Workorder(workorderId))
                .technicianId(technicianId)
                .assignedBy(assignedBy)
                .assignedAt(now)
                .notes(notes)
                .current(true)
                .build();

        TechnicianAssignment saved = saveHonouringSingleCurrentIndex(assignment, workorderId);
        log.info("Assigned technician {} to workorder {} by user {}", technicianId, workorderId, assignedBy);

        // #2011: a technician is half of what ASSIGNED means — the workorder also has to stand on a
        // bay or a mobile unit. The state machine decides, so a workorder with nowhere to be worked
        // stays APPROVED until it is placed, and this no longer has to know the rule itself.
        stateMachine.reconcileAssigned(workorderId, assignedBy, "Technician assigned");

        return TechnicianAssignmentRecord.fromEntity(saved);
    }

    /**
     * Reassign a workorder to a different technician.
     *
     * <p>
     * This is semantically similar to assignTechnician but explicitly indicates
     * that the workorder had a previous assignment. The reason is captured in the
     * new assignment record.
     *
     * @param workorderId     the workorder ID
     * @param newTechnicianId the new technician ID
     * @param reassignedBy    the user ID performing the reassignment
     * @param reason          the reason for reassignment
     * @param notes           optional additional notes
     * @return the new assignment record
     * @throws WorkorderNotFoundException if workorder not found
     * @throws TechnicianNotAssignedException if the workorder has no current technician to reassign from
     * @throws IllegalStateException  if workorder status doesn't allow reassignment
     */
    @Transactional
    @NonNull
    public TechnicianAssignmentRecord reassignTechnician(
            @NonNull UUID workorderId,
            @NonNull UUID newTechnicianId,
            @NonNull String reassignedBy,
            @Nullable String reason,
            @Nullable String notes) {

        Workorder workorder = workorderRepository
                .findById(workorderId)
                .orElseThrow(() -> new WorkorderNotFoundException(workorderId));

        // Validate status
        validateAssignmentAllowed(workorder);

        // Ensure there's a current assignment to reassign from
        // Reassign is deliberately not promoted to an assign when there is nobody to reassign from
        // (#1985): a caller that believed the workorder was held and gets a 200 has learned nothing
        // about its view being stale, which is the same ambiguity assign's new 409 removes.
        requireKnownTechnician(newTechnicianId);

        TechnicianAssignment currentAssignment = assignmentRepository
                .findCurrentForUpdate(workorderId)
                .orElseThrow(() -> new TechnicianNotAssignedException(
                        "Cannot reassign: workorder " + workorderId + " has no current technician assignment"));

        // The no-current-assignment guard above is authoritative over staffing: a reassign against a
        // workorder nobody holds is always 409 TECHNICIAN_NOT_ASSIGNED, even when the replacement is
        // staffed only elsewhere.
        requireStaffedAtSite(newTechnicianId, workorder);

        UUID previousTechnicianId = currentAssignment.getTechnicianId();
        log.debug(
                "Reassigning workorder {} from technician {} to {}",
                workorderId,
                previousTechnicianId,
                newTechnicianId);

        // Close the outgoing row and flush it before the incoming one is written. The flush is
        // load-bearing, not defensive: Hibernate's action queue runs every INSERT before any UPDATE,
        // so a plain save() here would have the new current row inserted while the old one still
        // says current = true, and the partial unique index
        // technician_assignment_one_current_uniq would refuse the reassignment outright (#1985).
        currentAssignment.markAsNotCurrent(LocalDateTime.now(clock), reason, null);
        assignmentRepository.saveAndFlush(currentAssignment);

        // Create new assignment
        LocalDateTime now = LocalDateTime.now(clock);
        TechnicianAssignment newAssignment = TechnicianAssignment.builder()
                .workorder(new Workorder(workorderId))
                .technicianId(newTechnicianId)
                .assignedBy(reassignedBy)
                .assignedAt(now)
                .notes(notes)
                .reassignmentReason(reason)
                .current(true)
                .build();

        TechnicianAssignment saved = saveHonouringSingleCurrentIndex(newAssignment, workorderId);
        log.info(
                "Reassigned workorder {} from technician {} to {} by user {}",
                workorderId,
                previousTechnicianId,
                newTechnicianId,
                reassignedBy);

        // #2011: a hand-over leaves the pair complete, so an ASSIGNED workorder stays ASSIGNED. Asked
        // anyway rather than skipped: an APPROVED workorder that was already on a bay and had somehow
        // lost its status is put right here, and the rule stays in the one place that owns it.
        stateMachine.reconcileAssigned(workorderId, reassignedBy, "Technician reassigned");

        return TechnicianAssignmentRecord.fromEntity(saved);
    }

    @Override
    @Transactional
    public Optional<TechnicianAssignmentRecord> releaseAssignment(
            @NonNull UUID workorderId, @NonNull String releasedBy, @Nullable String reason) {
        // Locked, not a plain read (#1985): a release that read T1 and a reassignment that replaces
        // T1 with T2 would otherwise both succeed, and the release would report the workorder
        // unassigned while T2 holds it. The lock makes the second one wait and see the truth.
        Optional<TechnicianAssignment> currentAssignment = assignmentRepository.findCurrentForUpdate(workorderId);
        if (currentAssignment.isEmpty()) {
            return Optional.empty();
        }
        TechnicianAssignment assignment = currentAssignment.get();
        assignment.markAsNotCurrent(LocalDateTime.now(clock), reason, releasedBy);
        TechnicianAssignment saved = assignmentRepository.saveAndFlush(assignment);
        // #2010: the workorder is no longer held by anyone, so ASSIGNED would be a lie — the dispatch
        // board and the shop dashboard would go on showing a job as assigned that nobody holds. The
        // revert goes through the state machine like any other transition, so it leaves a status
        // history row and publishes a status event. Flushed first so the reconciliation, which reads
        // the current-assignment row back, sees this release rather than the row it just closed.
        //
        // The decision is made after the lock above and from the locked row, so a release racing a
        // reassignment cannot revert a workorder the winner has just handed to somebody else.
        stateMachine.reconcileAssigned(
                workorderId, releasedBy, reason == null || reason.isBlank() ? "Technician released" : reason);
        log.info(
                "Released technician {} from workorder {} by {}: {}",
                assignment.getTechnicianId(),
                workorderId,
                releasedBy,
                reason);
        return Optional.of(TechnicianAssignmentRecord.fromEntity(saved));
    }

    /**
     * Get the current assignment for a workorder.
     *
     * @param workorderId the workorder ID
     * @return optional containing the current assignment if one exists
     */
    @NonNull
    public Optional<TechnicianAssignmentRecord> getCurrentAssignment(@NonNull UUID workorderId) {
        return assignmentRepository
                .findByWorkorder_IdAndCurrentTrue(workorderId)
                .map(TechnicianAssignmentRecord::fromEntity);
    }

    /**
     * Get the full assignment history for a workorder, ordered newest-first.
     *
     * @param workorderId the workorder ID
     * @return list of assignments ordered by assignedAt descending
     */
    @NonNull
    public List<TechnicianAssignmentRecord> getAssignmentHistory(@NonNull UUID workorderId) {
        return assignmentRepository.findByWorkorder_IdOrderByAssignedAtDesc(workorderId).stream()
                .map(TechnicianAssignmentRecord::fromEntity)
                .toList();
    }

    /**
     * Get the previous technician ID if this workorder was reassigned.
     *
     * @param workorderId the workorder ID
     * @return optional containing the previous technician ID if there was a
     *         reassignment
     */
    @NonNull
    public Optional<UUID> getPreviousTechnicianId(@NonNull UUID workorderId) {
        List<TechnicianAssignmentRecord> history = getAssignmentHistory(workorderId);
        if (history.size() >= 2) {
            // Second most recent assignment is the previous one
            return Optional.of(history.get(1).technicianId());
        }
        return Optional.empty();
    }

    /**
     * Get the current workorder status.
     *
     * @param workorderId the workorder ID
     * @return the workorder status
     * @throws WorkorderNotFoundException if workorder not found
     */
    @NonNull
    public WorkorderStatus getWorkorderStatus(@NonNull UUID workorderId) {
        return workorderRepository
                .findById(workorderId)
                .map(Workorder::getStatus)
                .orElseThrow(() -> new WorkorderNotFoundException(workorderId));
    }

    /**
     * Refuse a technician this module has never heard of (#1983).
     *
     * <p>Checked against the {@code ext_person} replica pos-people feeds, not by a synchronous call
     * into that service (ADR-0044 §6). 422 rather than 404: the technician is not what the URL
     * addresses, and the request is well-formed — it names someone who does not exist here.
     *
     * <p>This deliberately checks existence only. Whether a technician may hold a workorder at a
     * site they are not staffed at is a separate question, answered by {@link #requireStaffedAtSite}
     * (#1990): a technician must be staffed at the workorder's site, with no override.
     */
    private void requireKnownTechnician(@NonNull UUID technicianId) {
        if (!extPersonReplicaRepository.existsById(technicianId)) {
            throw new TechnicianNotFoundException(technicianId);
        }
    }

    /**
     * Refuse a technician who is not staffed at the workorder's site (#1990). There is no override.
     *
     * <p>pos-people owns the technician-to-site staffing relation; it is read only from the
     * {@code ext_people_staffing_assignment} replica (ADR-0044 §6), never by a synchronous call into
     * that service. {@link PeopleAvailabilityLocalService#isEligibleAtSite} is the single definition
     * of "staffed here": ACTIVE, effective today, {@code is_primary} not considered, role not
     * filtered on — and a technician with no ACTIVE staffing rows at all is let through, because
     * replica lag, bootstrap and a stalled DLQ must not take a shop offline.
     *
     * <p>This runs only at the moment a technician is assigned or reassigned — from {@link
     * #assignTechnician} and {@link #reassignTechnician}, both after the assign-vs-reassign conflict
     * guard so that guard stays authoritative over staffing. It never runs again afterward, and
     * nothing revalidates a workorder's already-assigned technician if the workorder's site changes
     * later: {@code WorkorderServiceImpl.handleAssignmentUpdated} (the inbound
     * {@code AssignmentUpdatedEvent} handler) and {@code WorkorderServiceImpl.overrideOperationalContext}
     * both write {@code Workorder.locationId} while a current {@link TechnicianAssignment} can remain
     * in place, and neither calls this method. A technician found staffed here at assignment time can
     * therefore end up parked at a site they are not staffed at if the workorder itself moves
     * afterward; closing that gap, if it is ever wanted, is separate future work.
     *
     * @param technicianId the technician being assigned or reassigned
     * @param workorder    the workorder they would hold, used to resolve the site to check against
     */
    private void requireStaffedAtSite(@NonNull UUID technicianId, @NonNull Workorder workorder) {
        UUID siteId = resolveSiteId(workorder);
        if (siteId == null) {
            // Nothing to compare against: either the workorder has no locationId and no mobile-unit
            // position, or (#1990 Finding 3) it is on a MOBILE_UNIT whose replica — or the replica's
            // baseLocationId — has not arrived yet. Deliberately not falling back to the workorder's
            // own locationId in that second case: ADR-0044 R3 treats absence of replicated data as
            // never a contradiction, and comparing against a different site would be a positive
            // answer manufactured from that absence, which is exactly what R3 forbids.
            return;
        }
        if (!peopleAvailabilityLocalService.isEligibleAtSite(technicianId, siteId, LocalDate.now(clock))) {
            throw new TechnicianNotStaffedAtSiteException(technicianId, siteId);
        }
    }

    /**
     * The site a technician must be staffed at to hold this workorder (#1990), or {@code null} when
     * there is nothing to compare against.
     *
     * <p>For {@link ResourceType#BAY}, {@link ResourceType#HOLD} and a workorder with no position
     * yet, the site is the workorder's own {@code locationId}.
     *
     * <p>For {@link ResourceType#MOBILE_UNIT} the site is the unit's own {@code
     * ExtMobileUnitReplica.baseLocationId} — never the workorder's own {@code locationId}. The two
     * are not always the same value: {@link ServicePositionServiceImpl#requireSameSite} forces them
     * to agree only for a position placed through {@link ServicePositionServiceImpl#assignPosition},
     * the dispatcher's own placement endpoint. {@code WorkorderServiceImpl.handleAssignmentUpdated}
     * and {@code WorkorderServiceImpl.overrideOperationalContext} write {@code locationId} and the
     * resource together from an inbound event or an override request, without going through {@code
     * resolvePosition}/{@code requireSameSite}, so a mobile-unit workorder's {@code locationId} can
     * genuinely diverge from its unit's base site. If the replica has not arrived yet, or its {@code
     * baseLocationId} is null, this returns {@code null} rather than the workorder's {@code
     * locationId} (#1990 Finding 3) — see {@link #requireStaffedAtSite} for why.
     */
    @Nullable
    private UUID resolveSiteId(@NonNull Workorder workorder) {
        if (workorder.getResourceType() == ResourceType.MOBILE_UNIT && workorder.getResourceId() != null) {
            return extMobileUnitReplicaRepository
                    .findById(workorder.getResourceId())
                    .map(ExtMobileUnitReplica::getBaseLocationId)
                    .orElse(null);
        }
        return workorder.getLocationId();
    }

    /**
     * Flush the new assignment now so a lost race surfaces as the same 409 the pre-check raises.
     *
     * <p>Two dispatchers assigning the same free workorder both read no current assignment — neither
     * sees the other's uncommitted row — and the partial unique index
     * {@code technician_assignment_one_current_uniq} is what decides between them (#1985). Without
     * the explicit flush the loser's violation would surface at commit, outside this method and
     * outside the {@code @ExceptionHandler} that knows what it means, so an ordinary expected
     * refusal would reach the client as a 500. The current technician is left null: the winner is
     * whichever transaction committed first, and this one cannot see it from its own snapshot.
     */
    @NonNull
    private TechnicianAssignment saveHonouringSingleCurrentIndex(
            @NonNull TechnicianAssignment assignment, @NonNull UUID workorderId) {
        try {
            return assignmentRepository.saveAndFlush(assignment);
        } catch (DataIntegrityViolationException ex) {
            log.info("Concurrent technician assignment lost the race on workorder {}", workorderId, ex);
            throw new TechnicianAlreadyAssignedException(
                    "Workorder " + workorderId + " was assigned to a technician by another request", null);
        }
    }

    @Override
    public void requireOpenWorkorder(@NonNull UUID workorderId) {
        Workorder workorder = workorderRepository
                .findById(workorderId)
                .orElseThrow(() -> new WorkorderNotFoundException(workorderId));
        if (workorder.isLocked()) {
            throw new WorkorderClosedException(
                    workorderId,
                    workorder.getStatus() == null
                            ? "closed"
                            : workorder.getStatus().name());
        }
    }

    /**
     * Validate that assignment is allowed for the workorder's current status.
     *
     * @param workorder the workorder
     * @throws IllegalStateException if assignment is not allowed
     */
    private void validateAssignmentAllowed(@NonNull Workorder workorder) {
        WorkorderStatus status = workorder.getStatus();
        // A closed workorder gets the same stable code the position operations use (#1983). Without
        // this it fell through to the IllegalStateException below, which the controller renders as a
        // 400 — so "this job is over" was indistinguishable from "this status cannot take a
        // technician yet", and the two need different things from the caller.
        if (workorder.isLocked()) {
            throw new WorkorderClosedException(workorder.getId(), status == null ? "closed" : status.name());
        }
        if (status != WorkorderStatus.APPROVED
                && status != WorkorderStatus.ASSIGNED
                && status != WorkorderStatus.WORK_IN_PROGRESS) {
            throw new IllegalStateException(
                    "Cannot assign technician: workorder must be in APPROVED, ASSIGNED, or WORK_IN_PROGRESS status. Current status: "
                            + status);
        }
    }
}
