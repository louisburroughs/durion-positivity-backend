package com.positivity.domainevents.customer;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: an audience segment's definition changed or it was deleted (Story #1137).
 *
 * <p>Published by pos-customer on {@code customer.events.v1} with
 * {@code eventType = "customer.segment.changed"}. Carries metadata only — never membership.
 * A dynamic segment's membership is derived from party data and has no event boundary, so it
 * is obtained on request via the {@code SEGMENT_RESOLVE_REQUESTED} command instead.
 *
 * @param segmentId segment identifier (also the envelope aggregateId)
 * @param name segment name at emission time
 * @param audienceType COMMERCIAL or INDIVIDUAL
 * @param type STATIC or DYNAMIC
 * @param active whether the segment may be bound to a campaign
 * @param deleted {@code true} when the segment was removed
 */
public record CustomerSegmentChangedV1(
        @NonNull UUID segmentId,
        @Nullable String name,
        @Nullable String audienceType,
        @Nullable String type,
        boolean active,
        boolean deleted) {

    public static final String EVENT_TYPE = "customer.segment.changed";

    public CustomerSegmentChangedV1 {
        if (segmentId == null) {
            throw new IllegalArgumentException("segmentId must not be null");
        }
    }
}
