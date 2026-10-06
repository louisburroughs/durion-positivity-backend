package com.positivity.supplier.internal.service;

import com.positivity.domainevents.DomainTopics;
import com.positivity.supplier.internal.repository.SupplierOutboxEventRepository;
import com.positivity.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-queues the bound tenant's published {@code supplier.events.v1} rows of one window for
 * re-publication (ADR-0044 §4, #2516): the {@code supplier.outbox.replay-requested} command's work.
 *
 * <p>The publisher re-sends each row's stored envelope, so a replayed event carries its original event
 * id and consumers dedupe it. Only the tenant the command was consumed under (its {@code tenantId}
 * header, ADR-0062 §3) is touched: one tenant's drift never re-sends another tenant's events. An unbound
 * call fails closed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierOutboxReplayService {

    /** The topic a replay re-queues: the facts topic the reconciliation manifests cover. */
    static final String EVENTS_TOPIC = DomainTopics.events("supplier");

    private final SupplierOutboxEventRepository outboxRepository;

    /**
     * Re-queues the bound tenant's published fact rows created in {@code [since, until)}.
     *
     * @return how many rows were re-queued
     */
    @Transactional
    public int replayEventsBetween(@NonNull Instant since, @NonNull Instant until) {
        UUID tenantId = TenantContext.require();
        int count = outboxRepository.markForReplayBetween(tenantId, EVENTS_TOPIC, since, until);
        log.info(
                "Supplier outbox replay tenant={} topic={} window=[{}, {}) eventsQueued={}",
                tenantId,
                EVENTS_TOPIC,
                since,
                until,
                count);
        return count;
    }
}
