package com.positivity.workorder.internal.service;

import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantIterator;
import com.positivity.workorder.internal.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replays only the rows of the tenant bound to the thread (ADR-0062 §3): the replay command arrives
 * with the requesting manifest's tenant header, so one tenant's drift never re-sends another
 * tenant's events. An unbound call fails closed with {@code TenantContextMissingException}. The
 * administrative entry point ({@link #replaySinceForCaller}) is the one place that fans out: a
 * platform-tenant operator replays every active tenant's rows, each under that tenant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxReplayServiceImpl implements OutboxReplayService {

    private final OutboxEventRepository outboxEventRepository;
    private final TenantIterator tenantIterator;

    @Override
    @Transactional
    public int replaySince(@NonNull Instant since) {
        UUID tenantId = TenantContext.require();
        int count = outboxEventRepository.markForReplaySince(tenantId, since);
        log.info("Outbox replay requested tenant={} since={} eventsQueued={}", tenantId, since, count);
        return count;
    }

    @Override
    @Transactional
    public int replaySinceForCaller(@NonNull Instant since) {
        UUID caller = TenantContext.require();
        if (!PlatformTenant.isPlatform(caller)) {
            return replaySince(since);
        }
        // The platform tenant owns no workorder rows; an operator bound to it asks for the fleet.
        AtomicInteger queued = new AtomicInteger();
        int tenants = tenantIterator.forEachActiveTenant(
                tenantId -> queued.addAndGet(outboxEventRepository.markForReplaySince(tenantId, since)));
        log.info(
                "Outbox replay requested by a platform operator since={} tenants={} eventsQueued={}",
                since,
                tenants,
                queued.get());
        return queued.get();
    }

    @Override
    @Transactional
    public int replayBetween(@NonNull Instant since, @NonNull Instant until) {
        UUID tenantId = TenantContext.require();
        int count = outboxEventRepository.markForReplayBetween(tenantId, since, until);
        log.info("Outbox replay requested tenant={} window=[{}, {}) eventsQueued={}", tenantId, since, until, count);
        return count;
    }
}
