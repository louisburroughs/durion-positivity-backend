package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.entity.WorkorderFleetAuthorization;
import com.positivity.workorder.internal.repository.WorkorderFleetAuthorizationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

    private final WorkorderFleetAuthorizationRepository authorizationRepository;

    /**
     * The per-row release, in a separate bean so it gets a real transaction: a self-call from this
     * class's {@code @Scheduled} tick would bypass the transaction proxy.
     */
    private final FleetAuthorizationResourceReleaser resourceReleaser;

    private final Clock clock;
    private final Duration releaseAfter;
    private final int batchSize;

    public FleetAuthorizationResourceReleaseRunner(
            WorkorderFleetAuthorizationRepository authorizationRepository,
            FleetAuthorizationResourceReleaser resourceReleaser,
            Clock clock,
            @Value("${workorder.fleetauth.resource-release-after:PT4H}") Duration releaseAfter,
            @Value("${workorder.fleetauth.resource-release-batch-size:50}") int batchSize) {
        this.authorizationRepository = authorizationRepository;
        this.resourceReleaser = resourceReleaser;
        this.clock = clock;
        this.releaseAfter = releaseAfter;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${workorder.fleetauth.resource-release-interval-ms:900000}")
    public void releaseOverdue() {
        Instant releaseDueBefore = Instant.now(clock).minus(releaseAfter);
        List<WorkorderFleetAuthorization> due = authorizationRepository.findDueForResourceRelease(
                releaseDueBefore, FleetAuthorizationResourceReleaser.BLOCKING_STATUSES, Limit.of(batchSize));

        for (WorkorderFleetAuthorization authorization : due) {
            try {
                resourceReleaser.releaseOne(authorization);
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
}
