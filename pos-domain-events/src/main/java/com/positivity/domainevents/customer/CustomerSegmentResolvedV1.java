package com.positivity.domainevents.customer;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Fact: a segment was resolved to its matching parties, in reply to a
 * {@code SEGMENT_RESOLVE_REQUESTED} command (Story #1137).
 *
 * <p>Published by pos-customer on {@code customer.events.v1} with
 * {@code eventType = "customer.segment.resolved"}.
 *
 * <p>This is the asynchronous replacement for a synchronous membership read. Dynamic membership
 * is computed from party data and has no event boundary of its own, so it cannot be replicated
 * continuously — a requester asks, and the owner answers.
 *
 * <p>{@code partyIds} carries opaque identifiers only: no names, addresses, or contact details.
 *
 * @param requestId correlates the reply to the request that asked for it
 * @param segmentId segment resolved (also the envelope aggregateId)
 * @param audienceType the segment's audience, so the requester need not hold a second replica
 * @param partyIds matching parties, ordered
 * @param truncated whether the owner's candidate scan hit its ceiling and the list is partial
 */
public record CustomerSegmentResolvedV1(
        @NonNull UUID requestId,
        @NonNull UUID segmentId,
        @NonNull String audienceType,
        @NonNull List<UUID> partyIds,
        boolean truncated) {

    public static final String EVENT_TYPE = "customer.segment.resolved";

    public CustomerSegmentResolvedV1 {
        if (requestId == null) {
            throw new IllegalArgumentException("requestId must not be null");
        }
        if (segmentId == null) {
            throw new IllegalArgumentException("segmentId must not be null");
        }
        partyIds = partyIds == null ? List.of() : List.copyOf(partyIds);
    }
}
