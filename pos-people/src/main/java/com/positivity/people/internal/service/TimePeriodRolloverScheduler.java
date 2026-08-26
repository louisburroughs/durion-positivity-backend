package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.TimePeriodRolloverResult;
import com.positivity.people.service.TimePeriodManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled driver for the pay-period rollover (issue #1527), mirroring the
 * {@code pos.inventory.replenishment.scan.*} scheduling convention. The pass is idempotent:
 * period creation is guarded by an overlap check plus the {@code (tenant_id, start_date)}
 * unique constraint, and status advancement only ever moves a period forward, so overlapping
 * runs from multiple instances cannot duplicate or regress periods.
 *
 * <p>Enabled by default ({@code pos.people.time-period.rollover.enabled}); disable per
 * environment when periods are managed exclusively through the operator API.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        prefix = "pos.people.time-period.rollover",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TimePeriodRolloverScheduler {

    private final TimePeriodManagementService timePeriodManagementService;

    @Scheduled(cron = "${pos.people.time-period.rollover.cron:0 15 0 * * *}", zone = "UTC")
    public void runScheduledRollover() {
        try {
            TimePeriodRolloverResult result = timePeriodManagementService.runRollover();
            log.info(
                    "Scheduled time-period rollover: periodsCreated={} submissionsClosed={} payrollsClosed={}",
                    result.getPeriodsCreated(),
                    result.getSubmissionsClosed(),
                    result.getPayrollsClosed());
        } catch (Exception ex) {
            // Scheduled job: never let one failed pass escalate; the next pass retries.
            log.error("Scheduled time-period rollover failed", ex);
        }
    }
}
