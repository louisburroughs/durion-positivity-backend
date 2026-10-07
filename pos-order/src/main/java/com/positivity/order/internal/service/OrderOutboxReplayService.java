package com.positivity.order.internal.service;

import com.positivity.domainevents.DomainTopics;
import com.positivity.order.internal.repository.OutboxEventRepository;
import com.positivity.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-queues the bound tenant's published {@code order.events.v1} rows for re-publication (ADR-0044 §4,
 * #2579): the {@code order.outbox.replay-requested} command's work.
 *
 * <p>The outbox publisher re-sends each row's stored envelope, so a replayed fact carries its original
 * event id and consumers dedupe it. Only the facts topic the reconciliation manifests cover is replayed,
 * never the commands this module queues on the same outbox for other owners. Only the tenant the command
 * was consumed under (its {@code tenantId} header, ADR-0062 §3) is touched: one tenant's drift never
 * re-sends another tenant's facts. An unbound call fails closed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderOutboxReplayService {

    /** The topic a replay re-queues: the facts topic the reconciliation manifests cover. */
    static final String EVENTS_TOPIC = DomainTopics.events("order");

    private final OutboxEventRepository outboxEventRepository;

    /** Re-queues the bound tenant's published facts created at or after {@code since}; returns how many. */
    @Transactional
    public int replaySince(@NonNull Instant since) {
        UUID tenantId = TenantContext.require();
        int count = outboxEventRepository.markForReplaySince(tenantId, EVENTS_TOPIC, since);
        log.info(
                "Order outbox replay tenant={} topic={} since={} eventsQueued={}",
                tenantId,
                EVENTS_TOPIC,
                since,
                count);
        return count;
    }

    /** Re-queues the bound tenant's published facts created in {@code [since, until)}; returns how many. */
    @Transactional
    public int replayBetween(@NonNull Instant since, @NonNull Instant until) {
        UUID tenantId = TenantContext.require();
        int count = outboxEventRepository.markForReplayBetween(tenantId, EVENTS_TOPIC, since, until);
        log.info(
                "Order outbox replay tenant={} topic={} window=[{}, {}) eventsQueued={}",
                tenantId,
                EVENTS_TOPIC,
                since,
                until,
                count);
        return count;
    }
}
