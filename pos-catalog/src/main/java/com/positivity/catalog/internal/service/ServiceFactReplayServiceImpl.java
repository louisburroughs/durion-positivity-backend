package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.config.CatalogFactPublisher;
import com.positivity.catalog.internal.config.ServiceFactReplayService;
import com.positivity.catalog.internal.dto.ServiceFactReplayResultDto;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.repository.ServiceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounded, resumable re-emission of {@code catalog.service.updated} facts (#1306, ADR-0044 §4).
 *
 * <p>Deliberately the same shape as {@link ProductFactReplayServiceImpl}, and for the same reasons:
 *
 * <ul>
 *   <li><b>Same publisher as live traffic.</b> Facts come from {@link CatalogFactPublisher}, the one
 *       every ordinary service write already uses, so a replayed fact is indistinguishable from a
 *       live one. A parallel replay-specific serializer would drift from the first the moment the
 *       payload gained a field.
 *   <li><b>The consumers' existing stale guard is the ordering guarantee.</b>
 *       {@code aggregateVersion} is now the service's JPA {@code @Version} (#1486), not the retired
 *       {@code updatedAt} epoch-millis convention, so a replayed fact carries exactly the version
 *       the live row holds. Consumers apply on an equal version and skip only when they already
 *       hold something strictly greater — which is what makes replay-as-repair real rather than a
 *       silent no-op, and still cannot regress a replica holding something newer. New
 *       {@code eventId}s per re-emit are expected; consumers dedupe on them for redelivery, not for
 *       replay.
 *   <li><b>Paged rather than fire-and-forget,</b> so one call cannot bury live traffic behind a
 *       burst on the outbox and an operator can tell a slow replay from a stuck one. The cursor is
 *       the service id, not an offset, because offsets shift under concurrent writes.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceFactReplayServiceImpl implements ServiceFactReplayService {

    /** Bound on one call, so a mistyped limit cannot turn a replay into a broker flood. */
    public static final int MAX_LIMIT = 1000;

    private final ServiceRepository serviceRepository;
    private final CatalogFactPublisher catalogFactPublisher;
    private final Clock clock;

    @Override
    @NonNull
    @Transactional
    public ServiceFactReplayResultDto replayPage(
            @Nullable UUID afterServiceId, @Nullable Instant updatedSince, int limit) {
        // Refused rather than reported as a successful no-op. The publisher is deliberately silent
        // when pos.catalog.kafka.enabled is off, which is right for an ordinary write — the
        // business change is what matters — but a replay produces nothing else. Counting the rows
        // it read as facts it emitted would tell an operator a replica was seeded when the outbox
        // never saw a row, and the next thing they would do is trust it.
        if (!catalogFactPublisher.publicationEnabled()) {
            throw new CatalogBusinessRuleException(
                    "Fact publication is disabled (pos.catalog.kafka.enabled=false); a replay would emit nothing");
        }
        int pageSize = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Instant startedAt = Instant.now(clock);

        List<ServiceEntity> services =
                serviceRepository.findForReplay(afterServiceId, updatedSince, PageRequest.of(0, pageSize));

        for (ServiceEntity service : services) {
            catalogFactPublisher.publishServiceUpdated(service);
        }

        // A short page means the end was reached: the query is ordered by id and bounded by the
        // cursor, so there is nothing after it for this filter.
        boolean complete = services.size() < pageSize;
        UUID nextAfterId =
                complete || services.isEmpty() ? null : services.getLast().getId();

        log.info(
                "Replayed {} service facts (after={}, updatedSince={}, complete={})",
                services.size(),
                afterServiceId,
                updatedSince,
                complete);

        return new ServiceFactReplayResultDto(services.size(), nextAfterId, complete, updatedSince, startedAt);
    }
}
