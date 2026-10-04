package com.positivity.accounting.internal.service;

import com.positivity.tenancy.TenantIterator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules {@link EventIngestionService#processFailed(int)} (#2411), which until then had no caller.
 * Once per poll and per active tenant (ADR-0062, tenant bound) it re-runs the {@code FAILED} and
 * {@code SUSPENDED} events that are still under the attempt cap through the posting engine with the
 * current rules, skipping the reasons that do not change on a retry cadence ({@code PERIOD_CLOSED},
 * {@code CURRENCY_NOT_SUPPORTED}, see {@code PostingFailureReason#isExcludedFromAutoRetry()}); those
 * stay for the manual reprocess. {@code RECEIVED} events are not touched: they belong to {@link
 * ReceivedAccountingEventDrainer}.
 *
 * <p>The cadence is slow on purpose (default 15 minutes): the events are failures, and each pass
 * spends one of an event's {@code max-retries} attempts. Set {@code
 * pos.accounting.failed-event-retry.enabled=false} to switch it off.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "pos.accounting.failed-event-retry",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FailedAccountingEventRetryJob {

    private final EventIngestionService eventIngestionService;
    private final TenantIterator tenantIterator;
    private final int maxRetries;

    public FailedAccountingEventRetryJob(
            EventIngestionService eventIngestionService,
            TenantIterator tenantIterator,
            @Value("${pos.accounting.failed-event-retry.max-retries:3}") int maxRetries) {
        this.eventIngestionService = eventIngestionService;
        this.tenantIterator = tenantIterator;
        this.maxRetries = maxRetries;
    }

    /** One poll: every active tenant, one tenant's failure not stopping the others. */
    @Scheduled(
            initialDelayString = "${pos.accounting.failed-event-retry.initial-delay-ms:120000}",
            fixedDelayString = "${pos.accounting.failed-event-retry.poll-interval-ms:900000}")
    public void retryFailed() {
        tenantIterator.forEachActiveTenant(tenantId -> {
            int retried = eventIngestionService.processFailed(maxRetries);
            if (retried > 0) {
                log.info("Failed-event retry: tenant={} retried={} event(s)", tenantId, retried);
            }
        });
    }
}
