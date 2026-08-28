package com.positivity.catalog.internal.config;

import com.positivity.catalog.internal.dto.ProductFactReplayResultDto;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Re-emits {@code catalog.product.updated} facts so event-fed replicas can be seeded or repaired
 * (ADR-0044 §4, #1309).
 *
 * <p>ADR-0044 §4 has always required owners to provide a replay mechanism; pos-catalog had none,
 * and pos-supplier's PRICAT matching is the first consumer that cannot function without it — its
 * {@code ext_product_code} replica is built only from facts published after the consumer starts, so
 * on a fresh deployment it is empty and every vendor line quarantines.
 *
 * <p>The replay is a producer-side capability by design: no replica-holding module needs code for a
 * replay to reach it. Re-emitted facts are indistinguishable in content from live ones, so every
 * consumer applies them through its normal path, with its normal stale guard, and a replayed older
 * fact cannot regress a replica that already holds something newer.
 *
 * Issue: CAP-318 #1309
 */
public interface ProductFactReplayService {

    /**
     * Emits one bounded page of product facts.
     *
     * @param afterProductId resume cursor from a previous call; null starts at the beginning
     * @param updatedSince   restrict to products changed at or after this instant; null replays all
     * @param limit          maximum facts to emit in this call
     * @return what this page emitted and where to resume
     */
    @NonNull
    ProductFactReplayResultDto replayPage(@Nullable UUID afterProductId, @Nullable Instant updatedSince, int limit);
}
