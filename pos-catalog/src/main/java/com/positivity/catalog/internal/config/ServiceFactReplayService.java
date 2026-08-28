package com.positivity.catalog.internal.config;

import com.positivity.catalog.internal.dto.ServiceFactReplayResultDto;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Re-emits {@code catalog.service.updated} facts so event-fed replicas can be seeded or repaired
 * (ADR-0044 §4, #1306).
 *
 * <p>The service counterpart of {@link ProductFactReplayService}, and needed for the same reason
 * that one exists: a replica built only from facts published after the consumer started is empty on
 * a fresh deployment. pos-marketing resolves a campaign's {@code catalogFocusRef} against such a
 * replica, and {@code service:} is the form those references most often take — so without a replay,
 * a service becomes resolvable only when someone next happens to edit it.
 *
 * <p>Producer-side by design: no replica-holding module needs code for a replay to reach it.
 * Re-emitted facts are indistinguishable in content from live ones, so every consumer applies them
 * through its normal path and its normal stale guard, and a replayed older fact cannot regress a
 * replica that already holds something newer.
 *
 * <p>Deletions are the one thing a replay cannot reconstruct: a deleted service leaves no row to
 * replay, so its tombstone exists only in the live stream. A replica seeded from scratch therefore
 * learns the services that exist, not the ones that were removed — which is the correct outcome,
 * since a reference to a service that never reaches the replica is unresolvable either way.
 *
 * Issue: #1306
 */
public interface ServiceFactReplayService {

    /**
     * Emits one bounded page of service facts.
     *
     * @param afterServiceId resume cursor from a previous call; null starts at the beginning
     * @param updatedSince   restrict to services changed at or after this instant; null replays all
     * @param limit          maximum facts to emit in this call
     * @return what this page emitted and where to resume
     */
    @NonNull
    ServiceFactReplayResultDto replayPage(@Nullable UUID afterServiceId, @Nullable Instant updatedSince, int limit);
}
