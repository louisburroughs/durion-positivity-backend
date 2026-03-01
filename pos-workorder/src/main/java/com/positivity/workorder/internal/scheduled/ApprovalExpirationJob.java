package com.positivity.workorder.internal.scheduled;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.positivity.workorder.service.EstimateService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
     * Runs every hour at the top of the hour.
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour at :00
    public void expirePendingApprovals() {
        log.debug("Running approval expiration job");
        int expiredCount = estimateService.expirePendingApprovals();
        log.info("Approval expiration job completed - expired {} estimates", expiredCount);
    }
}
