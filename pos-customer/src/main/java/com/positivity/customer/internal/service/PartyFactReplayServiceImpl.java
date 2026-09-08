package com.positivity.customer.internal.service;

import com.positivity.customer.internal.config.PartyFactReplayService;
import com.positivity.customer.internal.dto.PartyFactReplayResultDto;
import com.positivity.customer.internal.entity.AbstractParty;
import com.positivity.customer.internal.exception.CrmConflictException;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
 * Bounded, resumable re-emission of {@code customer.party.updated} facts (issue #1893, ADR-0044 §4).
 *
 * <h2>Same publisher as live traffic</h2>
 *
 * Facts are produced by {@link CustomerFactPublisher} — the one every ordinary party write already
 * uses — rather than by a parallel replay-specific serializer, so a replayed fact is
 * indistinguishable from a live one and consumers need not know which kind they are holding. A
 * second code path would drift from the first the moment the payload gained a field.
 *
 * <h2>One cursor over two tables</h2>
 *
 * Parties use {@code TABLE_PER_CLASS} inheritance, so there is no single queryable party table:
 * each concrete type is read through its own repository. Both queries are ordered by {@code partyId}
 * and bounded by the same cursor, so merging them and taking the first {@code limit} yields exactly
 * the page a single ordered table would have produced — and the cursor stays a single party id the
 * caller passes straight back, rather than one per type.
 *
 * <h2>Ordering and the stale guard</h2>
 *
 * A replayed fact carries exactly the {@code @Version} the live row holds (#1486). Consumers apply
 * on an equal version and skip only a strictly greater one, so a replay can never regress a replica
 * holding something newer — and applying on equal is what makes replay-as-repair real: a replica
 * that never saw a party at all, or silently dropped a field, is made whole rather than looking
 * like a no-op to the consumer's guard. New {@code eventId}s per re-emit are expected; consumers
 * dedupe on them for redelivery, not for replay.
 *
 * <h2>Why paged rather than fire-and-forget</h2>
 *
 * A full customer base is tens of thousands of facts. One request would either time out or bury
 * live traffic behind a burst on the outbox, and an operator would have no way to distinguish a
 * slow replay from a stuck one. The cursor is the party id, not an offset: offsets shift under
 * concurrent writes, and a party created mid-replay would push another out of the window — leaving
 * a replica short of exactly the fact the replay existed to deliver.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyFactReplayServiceImpl implements PartyFactReplayService {

    /** Bound on one call, so a mistyped limit cannot turn a replay into a broker flood. */
    public static final int MAX_LIMIT = 1000;

    private static final Comparator<AbstractParty> BY_ID = Comparator.comparing(AbstractParty::getPartyId);

    private final CommercialPartyRepository commercialPartyRepository;
    private final PersonPartyRepository personPartyRepository;
    private final CustomerFactPublisher factPublisher;
    private final Clock clock;

    @Override
    @NonNull
    @Transactional
    public PartyFactReplayResultDto replayPage(@Nullable UUID afterPartyId, @Nullable Instant updatedSince, int limit) {

        // Refused rather than reported as a successful no-op: with publication disabled the
        // publisher is silent, and counting the rows this read as facts it emitted would tell an
        // operator a replica was seeded when the outbox never saw a row.
        if (!factPublisher.publicationEnabled()) {
            throw new CrmConflictException(
                    "Fact publication is disabled (pos.customer.kafka.enabled=false); a replay would emit nothing");
        }
        int pageSize = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Instant startedAt = Instant.now(clock);
        PageRequest page = PageRequest.of(0, pageSize);

        // Each side returns up to pageSize parties after the cursor in id order, so the first
        // pageSize of the merge is the same page a single ordered party table would have given.
        List<AbstractParty> parties =
                new ArrayList<>(commercialPartyRepository.findForReplay(afterPartyId, updatedSince, page));
        parties.addAll(personPartyRepository.findForReplay(afterPartyId, updatedSince, page));
        parties.sort(BY_ID);

        // A merge shorter than the page means both tables were exhausted: each side returned fewer
        // than pageSize, and both are bounded by the same cursor, so nothing remains for this filter.
        boolean complete = parties.size() < pageSize;
        if (!complete) {
            parties = parties.subList(0, pageSize);
        }

        for (AbstractParty party : parties) {
            factPublisher.partyChanged(party);
        }

        UUID nextAfterId =
                complete || parties.isEmpty() ? null : parties.getLast().getPartyId();

        log.info(
                "Replayed {} party facts (after={}, updatedSince={}, complete={})",
                parties.size(),
                afterPartyId,
                updatedSince,
                complete);

        return new PartyFactReplayResultDto(parties.size(), nextAfterId, complete, updatedSince, startedAt);
    }
}
