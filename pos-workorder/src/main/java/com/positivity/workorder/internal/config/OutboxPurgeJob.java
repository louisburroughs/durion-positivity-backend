package com.positivity.workorder.internal.config;

import com.positivity.workorder.internal.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox retention (issue #838 decision Q8): purge published rows older than 90 days (default).
 *
 * <p>Unpublished rows are never touched. Purged windows can no longer be replayed via
 * {@code POST /v1/outbox/replay} — replay history equals retention. Revisit when a snapshot-style
 * re-emit lands (see #839).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class OutboxPurgeJob {

    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;

    @Value("${workorder.outbox.retention-days:90}")
    private long retentionDays;

    @Scheduled(cron = "${workorder.outbox.purge-cron:0 0 3 * * *}")
    @Transactional
    public void purge() {
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(retentionDays));
        int purged = outboxEventRepository.purgePublishedBefore(cutoff);
        if (purged > 0) {
            log.info("Outbox purge removed {} published rows older than {} ({} days)", purged, cutoff, retentionDays);
        }
    }
}
