package com.positivity.workorder.internal.service;

import com.positivity.security.common.LocationScope;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.dto.AssignServicePositionRequest;
import com.positivity.workorder.internal.dto.ServicePositionAssignmentRecord;
import com.positivity.workorder.internal.dto.ServicePositionResponse;
import com.positivity.workorder.internal.entity.ExtBayReplica;
import com.positivity.workorder.internal.entity.ExtMobileUnitReplica;
import com.positivity.workorder.internal.entity.ServicePositionAssignment;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.ResourceType;
import com.positivity.workorder.internal.exception.ServicePositionInactiveException;
import com.positivity.workorder.internal.exception.ServicePositionInvalidException;
import com.positivity.workorder.internal.exception.ServicePositionOccupiedException;
import com.positivity.workorder.internal.exception.WorkorderClosedException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.ExtBayReplicaRepository;
import com.positivity.workorder.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.workorder.internal.repository.ServicePositionAssignmentRepository;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.security.WorkorderPermissions;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service-position assignment for a workorder (#1983, #1984).
 *
 * <p>Two invariants live here. The first is that a position is only ever changed by an explicit
 * operation that leaves a history row — no silent overwrite, which is what
 * {@code overrideOperationalContext} used to do. The second is that an exclusive position (a bay or
 * a mobile unit) holds at most one open workorder, checked here so the caller gets a 409 naming the
 * occupant, and enforced underneath by the partial unique index
 * {@code workorder_open_position_uniq} so two concurrent assigns cannot both win. The check without
 * the index would be a race; the index without the check would be an opaque 500.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServicePositionServiceImpl implements ServicePositionService {

    private final Clock clock;
    private final WorkorderRepository workorderRepository;
    private final ServicePositionAssignmentRepository positionRepository;
    private final TechnicianAssignmentRepository technicianAssignmentRepository;
    private final ExtBayReplicaRepository extBayReplicaRepository;
    private final ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository;
    private final WorkorderFactPublisher workorderFactPublisher;

    private WorkorderStateMachine stateMachine;

    /**
     * Setter-injected and lazy because the dependency is genuinely circular (#2011): every status
     * change belongs to {@link WorkorderStateMachine}, including the ASSIGNED reconciliation these
     * operations must trigger, while the state machine calls back into this service to release a
     * closed workorder's position. Constructor injection of both ends cannot be resolved, and
     * duplicating the transition funnel here to avoid the cycle would put status changes in two
     * places, which is the thing the funnel exists to prevent.
     */
    @Autowired
    public void setStateMachine(@Lazy WorkorderStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @Override
    @Transactional
    @NonNull
    public ServicePositionResponse assignPosition(
            @NonNull UUID workorderId, @NonNull AssignServicePositionRequest request, @NonNull String actor) {

        Workorder workorder = loadOpenWorkorder(workorderId);
        requireLocationScope(workorder);

        ResourceType resourceType = request.getResourceType();
        UUID resourceId = resolvePosition(workorder, resourceType, request.getResourceId());

        recordPositionChange(workorder, resourceType, resourceId, actor, request.getReason());
        workorder.setResourceType(resourceType);
        workorder.setResourceId(resourceId);
        Workorder saved = savePositionChange(workorder);
        workorderFactPublisher.markChanged(workorderId);
        // #2011: the pair may have just been completed — a workorder that now holds both a technician
        // and a bay or mobile unit is ASSIGNED. Reconciled after the write, inside this transaction,
        // so the status the caller reads back already reflects the placement it just made.
        reconcileAssigned(workorderId, actor, "Service position assigned");

        log.info("Workorder {} placed on {} {} by {}", workorderId, resourceType, resourceId, actor);
        return buildResponse(saved);
    }

    @Override
    @Transactional
    @NonNull
    public ServicePositionResponse releasePosition(
            @NonNull UUID workorderId, @NonNull String actor, @Nullable String reason) {

        Workorder workorder = loadOpenWorkorder(workorderId);
        requireLocationScope(workorder);

        recordPositionChange(workorder, null, null, actor, reason);
        workorder.setResourceType(null);
        workorder.setResourceId(null);
        Workorder saved = savePositionChange(workorder);
        workorderFactPublisher.markChanged(workorderId);
        // #2011: an ASSIGNED workorder that gives up its bay or unit is no longer ready to be
        // worked, so it falls back to APPROVED.
        reconcileAssigned(
                workorderId, actor, reason == null || reason.isBlank() ? "Service position released" : reason);

        log.info("Workorder {} released its service position, by {}: {}", workorderId, actor, reason);
        return buildResponse(saved);
    }

    @Override
    @NonNull
    public ServicePositionResponse getPosition(@NonNull UUID workorderId) {
        Workorder workorder = workorderRepository
                .findById(workorderId)
                .orElseThrow(() -> new WorkorderNotFoundException(workorderId));
        return buildResponse(workorder);
    }

    @Override
    @Transactional
    public void releaseOnClose(@NonNull UUID workorderId, @NonNull String actor, @NonNull String reason) {
        Workorder workorder = workorderRepository.findById(workorderId).orElse(null);
        if (workorder == null) {
            // The replay paths transition an id the caller never read back. Nothing to release.
            return;
        }
        if (workorder.getResourceId() == null
                && positionRepository
                        .findByWorkorder_IdAndCurrentTrue(workorderId)
                        .isEmpty()) {
            return;
        }

        log.info(
                "Workorder {} closed ({}); releasing {} {}",
                workorderId,
                reason,
                workorder.getResourceType(),
                workorder.getResourceId());
        // The position is cleared, not merely marked released in history. Leaving the id on a closed
        // workorder would put it straight back into the occupancy index the moment the workorder is
        // reopened — reopening flips isReopened, which is exactly what the index's open predicate
        // reads — and by then the bay may belong to somebody else, so the reopen would fail on a
        // unique violation instead of succeeding. Where the work was done survives in the closed
        // history row, which is the durable record.
        recordPositionChange(workorder, null, null, actor, reason);
        workorder.setResourceType(null);
        workorder.setResourceId(null);
        workorderRepository.save(workorder);
    }

    @Override
    @Transactional
    public void recordPositionChange(
            @NonNull Workorder workorder,
            @Nullable ResourceType resourceType,
            @Nullable UUID resourceId,
            @NonNull String actor,
            @Nullable String reason) {

        UUID workorderId = workorder.getId();
        ResourceType effectiveType = resourceId == null ? null : resourceType;
        // A hold is the workorder's own site, on every path. HOLD became bindable on the legacy
        // override request the moment the enum gained it, and that path does not go through
        // resolvePosition — without this check it could persist (HOLD, some other id) and the
        // site-scoped hold would not be site-scoped at all.
        if (effectiveType == ResourceType.HOLD && !resourceId.equals(workorder.getLocationId())) {
            throw new ServicePositionInvalidException("A HOLD position is the workorder's own site: expected "
                    + workorder.getLocationId() + " but got " + resourceId);
        }
        // #2002: taking a bay or a mobile unit schedules the workorder if nothing else has. This sits
        // ahead of the unchanged-placement short-circuit below on purpose: an inbound assignment that
        // merely re-asserts the position a workorder already holds is exactly the traffic that would
        // otherwise leave an undated holder undated forever. HOLD is excluded because a parking lot
        // is not dispatch work — a vehicle can wait in the lot for a date nobody has set yet.
        if (effectiveType != null && effectiveType.isExclusive()) {
            LocalDate businessDate = LocalDate.now(clock);
            if (workorder.ensureScheduledForPosition(businessDate)) {
                log.info(
                        "Workorder {} had no scheduledDate when placed on {} {}; scheduling it for {}",
                        workorderId,
                        effectiveType,
                        resourceId,
                        businessDate);
            }
        }

        Optional<ServicePositionAssignment> currentPlacement =
                positionRepository.findByWorkorder_IdAndCurrentTrue(workorderId);

        // A repeat of the placement already in force is a no-op rather than a release-and-retake.
        // Dispatch boards and the inbound assignment event both re-send the current position on
        // every save, and honouring that literally would fill the history with pairs of rows
        // recording that nothing happened — and would briefly release a bay that never came free.
        boolean unchanged = currentPlacement
                .map(placement -> placement.getResourceType() == effectiveType
                        && placement.getResourceId().equals(resourceId))
                .orElse(resourceId == null);
        if (unchanged) {
            return;
        }

        if (effectiveType != null && effectiveType.isExclusive()) {
            requirePositionFree(workorderId, effectiveType, resourceId);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        currentPlacement.ifPresent(placement -> {
            placement.release(now, actor, reason);
            // Flushed, not merely saved: Hibernate's action queue runs every INSERT before any
            // UPDATE, so the incoming row would reach
            // service_position_assignment_one_current_uniq while the outgoing one still says
            // current = true, and the move would be refused.
            positionRepository.saveAndFlush(placement);
        });

        if (resourceId != null) {
            positionRepository.save(ServicePositionAssignment.builder()
                    .workorder(new Workorder(workorderId))
                    .resourceType(effectiveType)
                    .resourceId(resourceId)
                    .locationId(workorder.getLocationId())
                    .assignedAt(now)
                    .assignedBy(actor)
                    .reason(reason)
                    .current(true)
                    .build());
        }
    }

    /**
     * Let the state machine re-decide ASSIGNED for this workorder (#2011).
     *
     * <p>Null-tolerant so the unit tests that build this service by hand, and any caller that has
     * not wired the state machine, keep working: the reconciliation is a status refinement on top of
     * a placement that has already been persisted, never a precondition for it.
     */
    private void reconcileAssigned(@NonNull UUID workorderId, @NonNull String actor, @Nullable String reason) {
        if (stateMachine != null) {
            stateMachine.reconcileAssigned(workorderId, actor, reason);
        }
    }

    @NonNull
    private Workorder loadOpenWorkorder(@NonNull UUID workorderId) {
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
        return workorder;
    }

    /**
     * ADR-0061 §3: a caller whose grant is location-scoped may only move a workorder inside its own
     * reach. The workorder's shop is the gate, as it is for {@code overrideOperationalContext} — the
     * target position is at the workorder's own site by construction here (that is what
     * {@link #resolvePosition} enforces), so there is no second, different location to check.
     *
     * <p>The permission named here must be the one the endpoints reaching this method require
     * (#1890): since #2059 that is {@code workorder:position:assign}, not the override grant.
     */
    private void requireLocationScope(@NonNull Workorder workorder) {
        LocationScope scope = SecurityContextHelper.locationScope();
        scope.require(
                WorkorderPermissions.POSITION_ASSIGN,
                workorder.getShopId() == null ? "" : workorder.getShopId().toString());
    }

    /**
     * Resolve and validate the position being asked for, returning the id to store.
     *
     * <p>Every branch answers the same question — is this a real position at <em>this workorder's</em>
     * site — and a no is a 422 rather than a 404, because the position is not what the URL addresses.
     */
    @NonNull
    private UUID resolvePosition(
            @NonNull Workorder workorder, @NonNull ResourceType resourceType, @Nullable UUID requestedId) {

        UUID siteId = workorder.getLocationId();

        if (resourceType == ResourceType.HOLD) {
            if (siteId == null) {
                throw new ServicePositionInvalidException("Workorder " + workorder.getId()
                        + " has no locationId, so it has no site whose hold position it could occupy");
            }
            if (requestedId != null && !requestedId.equals(siteId)) {
                throw new ServicePositionInvalidException(
                        "A HOLD position is the workorder's own site: expected " + siteId + " but got " + requestedId);
            }
            return siteId;
        }

        if (requestedId == null) {
            throw new ServicePositionInvalidException("resourceId is required for resourceType " + resourceType);
        }

        if (resourceType == ResourceType.BAY) {
            ExtBayReplica bay = extBayReplicaRepository
                    .findById(requestedId)
                    .orElseThrow(() -> new ServicePositionInvalidException("Unknown bay " + requestedId));
            requireSameSite(resourceType, requestedId, bay.getLocationId(), siteId);
            requireActive(resourceType, requestedId, bay.isActive(), bay.getName());
            return requestedId;
        }

        ExtMobileUnitReplica unit = extMobileUnitReplicaRepository
                .findById(requestedId)
                .orElseThrow(() -> new ServicePositionInvalidException("Unknown mobile unit " + requestedId));
        requireSameSite(resourceType, requestedId, unit.getBaseLocationId(), siteId);
        requireActive(resourceType, requestedId, unit.isActive(), unit.getName());
        return requestedId;
    }

    private static void requireSameSite(
            @NonNull ResourceType resourceType,
            @NonNull UUID resourceId,
            @Nullable UUID positionSiteId,
            @Nullable UUID workorderSiteId) {
        if (workorderSiteId == null || !workorderSiteId.equals(positionSiteId)) {
            throw new ServicePositionInvalidException(resourceType + " " + resourceId + " belongs to site "
                    + positionSiteId + ", not to the workorder's site " + workorderSiteId);
        }
    }

    /**
     * Refuse a bay that is out of service or a mobile unit that is not deployed (#2001).
     *
     * <p>pos-location's status reaches this module on {@code location.bay.updated} /
     * {@code location.mobile-unit.updated} and lands on the replica's {@code active} column; until
     * this check existed nothing read it, so the dispatch board could show open work on a bay that
     * was out of service. Checked after the site check so a position belonging to another shop is
     * still reported as the wrong site rather than as inactive — the caller's first mistake is the
     * one worth naming.
     *
     * <p>A position that goes inactive while it already holds an open workorder is left in place and
     * flagged on the dispatch board rather than released (decided on #2001); this gate governs
     * taking a position, not keeping one.
     */
    private static void requireActive(
            @NonNull ResourceType resourceType, @NonNull UUID resourceId, boolean active, @Nullable String name) {
        if (!active) {
            throw new ServicePositionInactiveException(resourceType, resourceId, name);
        }
    }

    private void requirePositionFree(
            @NonNull UUID workorderId, @NonNull ResourceType resourceType, @NonNull UUID resourceId) {
        findOccupant(workorderId, resourceType, resourceId).ifPresent(occupant -> {
            throw new ServicePositionOccupiedException(
                    resourceType + " " + resourceId + " already holds open workorder " + occupant, occupant);
        });
    }

    @Override
    @NonNull
    public Optional<UUID> findOccupant(
            @NonNull UUID workorderId, @Nullable ResourceType resourceType, @Nullable UUID resourceId) {
        if (resourceId == null || resourceType == null || !resourceType.isExclusive()) {
            return Optional.empty();
        }
        return workorderRepository.findOpenOccupantsOfPosition(resourceType, resourceId, workorderId).stream()
                .map(Workorder::getId)
                .findFirst();
    }

    /**
     * Whether a bay or mobile unit is active, for the paths that must not throw (#2001).
     *
     * <p>Answers {@code true} for anything that is not an exclusive position, and for a position
     * whose replica row has not arrived yet: the inbound assignment fact is deliberately not
     * validated against the replicas (a dispatcher's own placement is, and gets a 422), so replica
     * lag must not make this module drop a position pos-shop-manager really did assign. Only a
     * replica row that positively says inactive is a no.
     */
    @Override
    public boolean isPositionActive(@Nullable ResourceType resourceType, @Nullable UUID resourceId) {
        if (resourceId == null || resourceType == null || !resourceType.isExclusive()) {
            return true;
        }
        if (resourceType == ResourceType.BAY) {
            return extBayReplicaRepository
                    .findById(resourceId)
                    .map(ExtBayReplica::isActive)
                    .orElse(true);
        }
        return extMobileUnitReplicaRepository
                .findById(resourceId)
                .map(ExtMobileUnitReplica::isActive)
                .orElse(true);
    }

    /**
     * Flush the workorder now so a lost race surfaces as the same 409 the pre-check raises.
     *
     * <p>Two dispatchers assigning the free bay at the same moment both pass the occupancy check —
     * each reads before the other commits — and the partial unique index is what decides between
     * them. Without the explicit flush the loser's violation would surface during commit, outside
     * this method and outside the {@code @ExceptionHandler} that knows what it means, and the caller
     * would get a 500 for what is an ordinary, expected refusal. The occupying workorder is left
     * null: the winner is whichever transaction committed first, and this one cannot see it from
     * inside its own snapshot.
     */
    @Override
    @NonNull
    public Workorder savePositionChange(@NonNull Workorder workorder) {
        try {
            return workorderRepository.saveAndFlush(workorder);
        } catch (DataIntegrityViolationException ex) {
            log.info(
                    "Concurrent assignment lost the race for {} {} on workorder {}",
                    workorder.getResourceType(),
                    workorder.getResourceId(),
                    workorder.getId(),
                    ex);
            throw new ServicePositionOccupiedException(
                    workorder.getResourceType() + " " + workorder.getResourceId()
                            + " was taken by another open workorder",
                    null);
        }
    }

    @NonNull
    private ServicePositionResponse buildResponse(@NonNull Workorder workorder) {
        UUID workorderId = workorder.getId();
        List<ServicePositionAssignmentRecord> history =
                positionRepository.findByWorkorder_IdOrderByAssignedAtDescIdDesc(workorderId).stream()
                        .map(ServicePositionAssignmentRecord::fromEntity)
                        .toList();

        return ServicePositionResponse.builder()
                .workorderId(workorderId)
                .locationId(workorder.getLocationId())
                .resourceType(workorder.getResourceId() == null ? null : workorder.getResourceType())
                .resourceId(workorder.getResourceId())
                .technicianId(technicianAssignmentRepository
                        .findByWorkorder_IdAndCurrentTrue(workorderId)
                        .map(assignment -> assignment.getTechnicianId())
                        .orElse(null))
                .workorderStatus(
                        workorder.getStatus() == null
                                ? null
                                : workorder.getStatus().name())
                .history(history)
                .build();
    }
}
