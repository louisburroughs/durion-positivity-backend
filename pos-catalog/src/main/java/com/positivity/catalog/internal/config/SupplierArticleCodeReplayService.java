package com.positivity.catalog.internal.config;

import com.positivity.catalog.internal.dto.SupplierArticleCodeReplayResultDto;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Bounded, resumable re-emission of {@code catalog.supplier-article-code.updated} facts (CAP-320
 * #1347, following #1309's pattern for {@code catalog.product.updated}).
 *
 * <p>A first deployment of pos-order's replica matches nothing: the replica holds only facts
 * published after its consumer started. This is the path an operator uses to seed or repair it,
 * without which every purchase-order line at a non-EAN vendor fails onboarding exactly as it does
 * today.
 */
public interface SupplierArticleCodeReplayService {

    /**
     * Emits one bounded page of vendor-article-code facts.
     *
     * @param afterId      resume cursor from a previous call; null starts at the beginning
     * @param updatedSince restrict to rows changed at or after this instant; null replays all
     * @param limit        maximum facts to emit in this call
     * @return what this page emitted and where to resume
     */
    @NonNull
    SupplierArticleCodeReplayResultDto replayPage(@Nullable UUID afterId, @Nullable Instant updatedSince, int limit);
}
