package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.OutboxReplayService;
import com.positivity.accounting.internal.repository.KafkaOutboxEventRepository;
import com.positivity.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks the requesting tenant's published {@code kafka_event_outbox} rows for re-publication; the
 * outbox publisher sends them again (ADR-0044 §4; CAP:550 S16, #2512). The tenant is the one the
 * replay command's record header bound, so one tenant's drift never replays another's facts
 * (ADR-0062 §3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxReplayServiceImpl implements OutboxReplayService {

    private final KafkaOutboxEventRepository outboxEventRepository;

    @Override
    @Transactional
    public int replaySince(@NonNull Instant since) {
        UUID tenantId = TenantContext.require();
        int count = outboxEventRepository.markForReplaySince(tenantId, since);
        log.info("Accounting outbox replay requested tenant={} since={} eventsQueued={}", tenantId, since, count);
        return count;
    }

    @Override
    @Transactional
    public int replayBetween(@NonNull Instant since, @NonNull Instant until) {
        UUID tenantId = TenantContext.require();
        int count = outboxEventRepository.markForReplayBetween(tenantId, since, until);
        log.info(
                "Accounting outbox replay requested tenant={} window=[{}, {}) eventsQueued={}",
                tenantId,
                since,
                until,
                count);
        return count;
    }
}
