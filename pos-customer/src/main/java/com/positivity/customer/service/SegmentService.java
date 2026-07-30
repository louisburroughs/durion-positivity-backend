package com.positivity.customer.service;

import com.positivity.customer.internal.dto.SegmentAttributeResponse;
import com.positivity.customer.internal.dto.SegmentMembersRequest;
import com.positivity.customer.internal.dto.SegmentResolutionResponse;
import com.positivity.customer.internal.dto.SegmentResponse;
import com.positivity.customer.internal.dto.UpsertSegmentRequest;
import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.MarketingChannel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Public API for saved audience segments (Story #1137).
 *
 * <p>Predicates are validated on save, not on resolve: a segment is authored once and
 * resolved repeatedly by scheduled campaigns with nobody watching, so a definition that only
 * fails at send time fails silently and late.
 */
public interface SegmentService {

    @NonNull
    SegmentResponse create(@NonNull UpsertSegmentRequest request);

    @NonNull
    SegmentResponse update(@NonNull UUID segmentId, @NonNull UpsertSegmentRequest request);

    void delete(@NonNull UUID segmentId);

    @NonNull
    SegmentResponse get(@NonNull UUID segmentId);

    @NonNull
    List<SegmentResponse> list(@Nullable AudienceType audienceType);

    /**
     * The whitelisted attribute catalog a dynamic predicate may reference (#1144) — wire
     * names, operand kinds, allowed operators, and descriptions, in catalog order.
     */
    @NonNull
    List<SegmentAttributeResponse> attributeCatalog();

    /** Pin parties into a STATIC segment. Rejected for DYNAMIC segments. */
    @NonNull
    SegmentResponse addMembers(@NonNull UUID segmentId, @NonNull SegmentMembersRequest request);

    void removeMember(@NonNull UUID segmentId, @NonNull UUID partyId);

    /**
     * Resolve to counts plus a masked sample. When {@code channel} is given, also reports how
     * many of the matches may actually be sent to after consent, account gate, and suppression.
     */
    @NonNull
    SegmentResolutionResponse resolve(@NonNull UUID segmentId, @Nullable MarketingChannel channel, int sampleSize);

    /**
     * Resolve a segment and publish {@code customer.segment.resolved} in reply to a
     * {@code SEGMENT_RESOLVE_REQUESTED} command.
     *
     * <p>Not exposed over HTTP. Dynamic membership is derived from party data and has no event
     * boundary, so it cannot be replicated continuously; a requester asks over
     * {@code customer.commands.v1} and this module answers with a fact carrying opaque
     * identifiers only — never names, addresses, or contact details.
     *
     * <p>Resolution and publication are deliberately one operation: it keeps the command
     * listener on this public interface rather than reaching into {@code internal.service} for
     * the fact publisher, which would create a cycle between the config and service slices.
     *
     * @return the number of parties published, or empty if the segment no longer exists
     */
    @NonNull
    Optional<Integer> resolveAndPublish(@NonNull UUID requestId, @NonNull UUID segmentId);
}
