package com.positivity.workorder.internal.scheduled;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateStatus;
import com.positivity.workorder.internal.repository.EstimateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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

    private final EstimateRepository estimateRepository;

    /**
     * Check for expired pending approvals and mark them as expired.
     * Runs every hour at the top of the hour.
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour at :00
    @Transactional
    public void expirePendingApprovals() {
        LocalDateTime now = LocalDateTime.now();
        log.debug("Running approval expiration job at {}", now);

        // Find all estimates in PENDING_APPROVAL state that have expired
        List<Estimate> expiredEstimates = estimateRepository.findByStatusAndExpiresAtBefore(
                EstimateStatus.PENDING_APPROVAL, now);

        if (expiredEstimates.isEmpty()) {
            log.debug("No expired pending approvals found");
            return;
        }

        log.info("Found {} expired pending approvals, marking them as expired", expiredEstimates.size());

        int successCount = 0;
        int errorCount = 0;

        for (Estimate estimate : expiredEstimates) {
            try {
                expireApproval(estimate, now);
                successCount++;
            } catch (Exception e) {
                errorCount++;
                log.error("Failed to expire approval for estimate {}: {}",
                        estimate.getId(), e.getMessage(), e);
            }
        }

        log.info("Approval expiration job completed - success: {}, errors: {}",
                successCount, errorCount);
    }

    /**
     * Mark an estimate as expired due to approval window timeout.
     * 
     * @param estimate estimate to expire
     * @param now      current timestamp
     */
    private void expireApproval(@NonNull Estimate estimate, @NonNull LocalDateTime now) {
        log.info("Expiring approval for estimate {} (expired at: {}, now: {})",
                estimate.getId(), estimate.getExpiresAt(), now);

        estimate.setStatus(EstimateStatus.EXPIRED);
        estimate.setDeclineReason("Approval window expired - customer did not respond within "
                + "the approval window that ended at " + estimate.getExpiresAt());
        estimate.setDeclinedAt(now);

        estimateRepository.save(estimate);

        log.info("Estimate {} marked as EXPIRED due to approval timeout", estimate.getId());
    }
}
