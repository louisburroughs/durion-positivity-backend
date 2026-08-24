package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.entity.WorkorderFleetAuthorization;
import com.positivity.workorder.internal.enums.FleetAuthorizationStatus;
import com.positivity.workorder.internal.repository.WorkorderFleetAuthorizationRepository;
import com.positivity.workorder.service.TechnicianAssignmentService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Releases one blocked workorder's held resources, as a single transaction.
 *
 * <h2>Why this is its own bean</h2>
 *
 * {@link #releaseOne} used to sit on {@link FleetAuthorizationResourceReleaseRunner} and be called on
 * {@code this} from its {@code @Scheduled} tick, which is not transactional. Spring's transaction
 * advice is proxy-based, so that self-call bypassed it and the method's own comment — "re-read the
 * guard inside the transaction" — described a transaction that did not exist. Living in a separate
 * bean means the call crosses the proxy and the guard, the assignment release and the
 * {@code resourcesReleasedAt} stamp are one unit.
 *
 * <h2>What the missing boundary cost</h2>
 *
 * The stamp is the only record that a release happened. Untransacted, an assignment could be
 * released and the stamp then fail to persist, leaving the row eligible again — so the next tick
 * releases an assignment that was already given up, attributing a second release to a job that never
 * regained the resource.
 */
@Slf4j
@Service
public class FleetAuthorizationResourceReleaser {

    /** Statuses under which held resources are still blocked and eligible for release. */
    static final Set<FleetAuthorizationStatus> BLOCKING_STATUSES = Set.of(
            FleetAuthorizationStatus.NOT_REQUESTED,
            FleetAuthorizationStatus.PENDING,
            FleetAuthorizationStatus.REFUSED,
            FleetAuthorizationStatus.MANUAL_REVIEW);

    private static final String RELEASED_BY = "fleet-authorization-resource-release";

    private final WorkorderFleetAuthorizationRepository authorizationRepository;
    private final TechnicianAssignmentService technicianAssignmentService;
    private final Clock clock;
    private final Duration releaseAfter;

    public FleetAuthorizationResourceReleaser(
            WorkorderFleetAuthorizationRepository authorizationRepository,
            TechnicianAssignmentService technicianAssignmentService,
            Clock clock,
            @Value("${workorder.fleetauth.resource-release-after:PT4H}") Duration releaseAfter) {
        this.authorizationRepository = authorizationRepository;
        this.technicianAssignmentService = technicianAssignmentService;
        this.clock = clock;
        this.releaseAfter = releaseAfter;
    }

    @Transactional
    public void releaseOne(@NonNull WorkorderFleetAuthorization authorization) {
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
