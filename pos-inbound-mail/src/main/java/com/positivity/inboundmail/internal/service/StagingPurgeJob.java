package com.positivity.inboundmail.internal.service;

import com.positivity.inboundmail.internal.config.InboundMailProperties;
import com.positivity.tenancy.TenantIterator;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly, once per active tenant with that tenant bound (ADR-0062 §3), deletes every staged attachment
 * older than {@code pos.inbound-mail.staging-ttl}: the bytes of a message pos-accounting never collected
 * do not outlive the TTL. One tenant's failure does not stop the others. Logs counts only (ADR-0072).
 */
@Slf4j
@Component
public class StagingPurgeJob {

    private final AttachmentStaging staging;
    private final TenantIterator tenantIterator;
    private final InboundMailProperties properties;
    private final Clock clock;

    public StagingPurgeJob(
            AttachmentStaging staging,
            TenantIterator tenantIterator,
            InboundMailProperties properties,
            Clock clock) {
        this.staging = staging;
        this.tenantIterator = tenantIterator;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${pos.inbound-mail.staging-purge-cron:0 15 3 * * *}")
    public void purgeExpiredAttachments() {
        Instant cutoff = Instant.now(clock).minus(properties.stagingTtl());
        tenantIterator.forEachActiveTenant(tenantId -> {
            int purged = staging.purgeStagedBefore(cutoff);
            log.info("Staging purge: tenant={} purged={} attachment(s)", tenantId, purged);
        });
    }
}
