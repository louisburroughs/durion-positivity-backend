package com.positivity.warranty.internal.service;

import com.positivity.tenancy.TenantContext;
import com.positivity.warranty.internal.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replays only the rows of the tenant bound to the thread (ADR-0062 §3): the replay command arrives
 * with the requesting manifest's tenant header, so one tenant's drift never re-sends another
 * tenant's events. An unbound call fails closed with {@code TenantContextMissingException}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxReplayServiceImpl implements OutboxReplayService {

    private final OutboxEventRepository outboxEventRepository;

    @Override
    @Transactional
    public int replaySince(@NonNull Instant since) {
        UUID tenantId = TenantContext.require();
        int count = outboxEventRepository.markForReplaySince(tenantId, since);
        log.info("Warranty outbox replay requested tenant={} since={} eventsQueued={}", tenantId, since, count);
        return count;
    }

    @Override
    @Transactional
    public int replayBetween(@NonNull Instant since, @NonNull Instant until) {
        UUID tenantId = TenantContext.require();
        int count = outboxEventRepository.markForReplayBetween(tenantId, since, until);
        log.info(
                "Warranty outbox replay requested tenant={} window=[{}, {}) eventsQueued={}",
                tenantId,
                since,
                until,
                count);
        return count;
    }
}
