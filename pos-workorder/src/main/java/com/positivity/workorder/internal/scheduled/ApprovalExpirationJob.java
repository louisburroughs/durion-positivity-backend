package com.positivity.workorder.internal.scheduled;

import com.positivity.workorder.service.EstimateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job to expire pending approvals that have exceeded their approval
 * window.
 * CAP:003 Issue #204 - Handle Approval Expiration
 *
 * Runs hourly to check for estimates in PENDING_APPROVAL state where expiresAt
 * has passed.
 * Transitions these estimates to EXPIRED state with reason explaining the
 * expiration.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApprovalExpirationJob {

    private final EstimateService estimateService;

    /**
     * Check for expired pending approvals and mark them as expired.
     *
     * <p>Polls on an interval rather than an hourly cron. The expiry comparison is made against the
     * injected application {@code Clock}, while a cron trigger fires against the scheduler's real
     * clock; under the accelerated profile one real hour spans weeks of application time, so a cron
     * would leave estimates pending long past their own expiry. This sweep is idempotent — it
     * selects whatever is PENDING_APPROVAL and already past {@code expiresAt} — so a pass that spans
     * many due boundaries is handled in one go.
     */
    @Scheduled(
            fixedDelayString = "${workorder.approval-expiration.interval-ms:3600000}",
            initialDelayString = "${workorder.approval-expiration.initial-delay-ms:60000}")
    public void expirePendingApprovals() {
        log.debug("Running approval expiration job");
        int expiredCount = estimateService.expirePendingApprovals();
        log.info("Approval expiration job completed - expired {} estimates", expiredCount);
    }
}
