package com.positivity.customer.internal.config;

import com.positivity.customer.internal.dto.PartyFactReplayResultDto;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Re-emits {@code customer.party.updated} for parties from their <em>current state</em>, so a
 * consumer replica can be seeded or repaired without waiting for the parties to be edited
 * (issue #1893, ADR-0044 §4).
 *
 * <h2>Why {@link OutboxReplayService} is not enough</h2>
 *
 * The generic outbox replay re-queues rows that already exist in {@code event_outbox}, within a
 * bounded lookback. That repairs drift on a consumer that was already live, but it cannot help a
 * consumer whose replica was introduced after the parties were: a party nobody has edited since has
 * no outbox row to replay, so its identity has never been published and the replica has nothing to
 * hold. That is exactly how pos-accounting's {@code ext_customer_party} ended up empty, leaving
 * {@code customerDisplayName} null on every credit memo (#1893). This replay reads the parties
 * themselves and rebuilds the facts, the same way pos-catalog's product-fact replay does for the
 * putaway replica columns (#1514).
 *
 * <h2>Idempotence</h2>
 *
 * The republished fact carries the party's current {@code @Version} — the same
 * {@code aggregateVersion} a replica may already hold. Consumers apply an equal version rather than
 * skipping it ({@code ReplicaVersionGuard}, #1486) precisely so a rebuild-from-state replay repairs
 * a replica holding the right version but wrong or missing data, and skip only a strictly newer
 * one, so a replay can never regress a replica that already holds something newer. Running a replay
 * twice therefore converges on the same state.
 */
public interface PartyFactReplayService {

    /**
     * Re-emit one bounded page of party facts, oldest party id first.
     *
     * @param afterPartyId resume cursor from a previous call; null starts at the beginning
     * @param updatedSince restrict to parties changed at or after this instant; null replays all
     * @param limit        maximum facts to emit in this call
     * @return what this page emitted and where to resume
     */
    @NonNull
    PartyFactReplayResultDto replayPage(@Nullable UUID afterPartyId, @Nullable Instant updatedSince, int limit);
}
