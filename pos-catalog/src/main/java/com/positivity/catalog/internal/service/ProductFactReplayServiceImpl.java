package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.config.CatalogFactPublisher;
import com.positivity.catalog.internal.dto.ProductFactReplayResultDto;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.service.ProductFactReplayService;
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
 * Bounded, resumable re-emission of {@code catalog.product.updated} facts (#1309, ADR-0044 §4).
 *
 * <h2>Same publisher as live traffic</h2>
 *
 * Facts are produced by {@link CatalogFactPublisher} — the one every ordinary product write already
 * uses — rather than by a parallel replay-specific serializer. That is the whole point: a replayed
 * fact must be indistinguishable from a live one, or consumers would need to know which kind they
 * are holding. A second code path would drift from the first the moment the payload gained a field,
 * which is precisely how #1224's product codes would have been missed.
 *
 * <h2>Ordering and the stale guard</h2>
 *
 * {@code aggregateVersion} is now the product's JPA {@code @Version} (#1486), not the retired
 * {@code updatedAt} epoch-millis convention, but the property this relies on is unchanged: a
 * replayed fact carries exactly the version the live row holds. Consumers apply on an equal
 * version and skip only when the version they already hold is strictly greater, so a replay can
 * never regress a replica that already holds something newer — and applying on equal is what makes
 * replay-as-repair real: a replica that silently dropped a field can be made whole again without
 * the replay looking like a no-op to the consumer's guard. New {@code eventId}s per re-emit are
 * expected; consumers dedupe on them for redelivery, not for replay.
 *
 * <h2>Why paged rather than fire-and-forget</h2>
 *
 * A full catalog is tens of thousands of facts. One request would either time out or bury live
 * traffic behind a burst on the outbox, and an operator would have no way to distinguish a slow
 * replay from a stuck one. Cursor paging over the product id keeps each call bounded, makes
 * progress visible between calls, and lets a replay be stopped and resumed. The cursor is the id,
 * not an offset: offsets shift under concurrent writes, and a product created mid-replay would push
 * another out of the window — leaving a replica short of exactly the fact the replay existed to
 * deliver.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductFactReplayServiceImpl implements ProductFactReplayService {

    /** Bound on one call, so a mistyped limit cannot turn a replay into a broker flood. */
    public static final int MAX_LIMIT = 1000;

    private final ProductRepository productRepository;
    private final CatalogFactPublisher catalogFactPublisher;
    private final Clock clock;

    @Override
    @NonNull
    @Transactional
    public ProductFactReplayResultDto replayPage(
            @Nullable UUID afterProductId, @Nullable Instant updatedSince, int limit) {
        // Refused rather than reported as a successful no-op: with publication disabled the
        // publisher is silent, and counting the rows this read as facts it emitted would tell an
        // operator a replica was seeded when the outbox never saw a row. Added alongside the same
        // guard on the service replay (#1306); the two are run back to back and lied alike.
        if (!catalogFactPublisher.publicationEnabled()) {
            throw new CatalogBusinessRuleException(
                    "Fact publication is disabled (pos.catalog.kafka.enabled=false); a replay would emit nothing");
        }
        int pageSize = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Instant startedAt = Instant.now(clock);

        List<ProductEntity> products =
                productRepository.findForReplay(afterProductId, updatedSince, PageRequest.of(0, pageSize));

        for (ProductEntity product : products) {
            catalogFactPublisher.publishProductUpdated(product);
        }

        // A short page means the catalog end was reached: the query is ordered by id and bounded by
        // the cursor, so there is nothing after it for this filter.
        boolean complete = products.size() < pageSize;
        UUID nextAfterId =
                complete || products.isEmpty() ? null : products.getLast().getId();

        log.info(
                "Replayed {} product facts (after={}, updatedSince={}, complete={})",
                products.size(),
                afterProductId,
                updatedSince,
                complete);

        return new ProductFactReplayResultDto(products.size(), nextAfterId, complete, updatedSince, startedAt);
    }
}
