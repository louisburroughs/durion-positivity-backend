package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.entity.WorkorderFleetAuthorization;
import com.positivity.workorder.internal.enums.FleetAuthorizationStatus;
import com.positivity.workorder.internal.repository.WorkorderFleetAuthorizationRepository;
import com.positivity.workorder.service.TechnicianAssignmentService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Frees capacity a blocked job cannot currently use (#1346).
 *
 * <h2>Delayed, and measured from the block, not the request</h2>
 *
 * A job whose fleet authorization is unresolved keeps its technician assignment for a configurable
 * grace period, then releases it. The delay exists because authorization can resolve in minutes and
 * releasing immediately would drop a booking that was about to become usable, at the cost of
 * re-assignment work nobody needed. It is measured from
 * {@link WorkorderFleetAuthorization#getFirstBlockedAt()} — the first time somebody tried to start
 * the job and could not — rather than from when the authorization was requested, because a job
 * nobody has tried to start is not holding a bay up regardless of how long the request has been
 * outstanding.
 *
 * <h2>The delay is configuration, not a constant</h2>
 *
 * The domain ruled that pending, retry, timeout and escalation rules differ by deployment and must
 * be configurable. This is the concrete instance: a shop with tight bay capacity wants a short grace
 * period, one with slack wants a long one, and neither should require a code change.
 *
 * <h2>Released once, and only while still blocking</h2>
 *
 * A row is released at most once, recorded by {@code resourcesReleasedAt}. If the authorization has
 * since been granted, cancelled, or resolved, nothing is released — releasing a resource that
 * belongs to a job now cleared to start would be actively wrong, not merely late.
 */
@Slf4j
@Service
public class FleetAuthorizationResourceReleaseRunner {

    /** Statuses under which held resources are still blocked and eligible for release. */
    private static final Set<FleetAuthorizationStatus> BLOCKING_STATUSES = Set.of(
            FleetAuthorizationStatus.NOT_REQUESTED,
            FleetAuthorizationStatus.PENDING,
            FleetAuthorizationStatus.REFUSED,
            FleetAuthorizationStatus.MANUAL_REVIEW);

    private static final String RELEASED_BY = "fleet-authorization-resource-release";

    private final WorkorderFleetAuthorizationRepository authorizationRepository;
    private final TechnicianAssignmentService technicianAssignmentService;
    private final Clock clock;
    private final Duration releaseAfter;
    private final int batchSize;

    public FleetAuthorizationResourceReleaseRunner(
            WorkorderFleetAuthorizationRepository authorizationRepository,
            TechnicianAssignmentService technicianAssignmentService,
            Clock clock,
            @Value("${workorder.fleetauth.resource-release-after:PT4H}") Duration releaseAfter,
            @Value("${workorder.fleetauth.resource-release-batch-size:50}") int batchSize) {
        this.authorizationRepository = authorizationRepository;
        this.technicianAssignmentService = technicianAssignmentService;
        this.clock = clock;
        this.releaseAfter = releaseAfter;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${workorder.fleetauth.resource-release-interval-ms:900000}")
    public void releaseOverdue() {
        Instant releaseDueBefore = Instant.now(clock).minus(releaseAfter);
        List<WorkorderFleetAuthorization> due = authorizationRepository.findDueForResourceRelease(
                releaseDueBefore, BLOCKING_STATUSES, Limit.of(batchSize));

        for (WorkorderFleetAuthorization authorization : due) {
            try {
                releaseOne(authorization);
            } catch (Exception e) {
                // One workorder's release failing must not stop the others. Nothing here is lost:
                // resourcesReleasedAt stays null, so this row is picked up again next tick.
                log.warn(
                        "Releasing resources for workorder {} blocked on fleet authorization failed: {}",
                        authorization.getWorkorderId(),
                        e.toString());
            }
        }
    }

    @Transactional
    void releaseOne(@NonNull WorkorderFleetAuthorization authorization) {
        // Re-read the guard inside the transaction: the status may have changed between the query
        // above and this write, and releasing a resource for a now-granted authorization would be
        // wrong rather than merely late.
        if (!BLOCKING_STATUSES.contains(authorization.getStatus()) || authorization.getResourcesReleasedAt() != null) {
            return;
        }

        technicianAssignmentService.releaseAssignment(
                authorization.getWorkorderId(),
                RELEASED_BY,
                "fleet payment authorization has been unresolved for over " + releaseAfter);

        authorization.setResourcesReleasedAt(Instant.now(clock));
        authorizationRepository.save(authorization);
        log.info(
                "Released held resources for workorder {}: fleet authorization has been {} since {}",
                authorization.getWorkorderId(),
                authorization.getStatus(),
                authorization.getFirstBlockedAt());
    }
}
