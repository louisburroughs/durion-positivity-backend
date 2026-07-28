package com.positivity.customer.service;

import com.positivity.customer.internal.dto.SegmentMembersRequest;
import com.positivity.customer.internal.dto.SegmentResolutionResponse;
import com.positivity.customer.internal.dto.SegmentResponse;
import com.positivity.customer.internal.dto.UpsertSegmentRequest;
import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.MarketingChannel;
import java.util.List;
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
     * The full matching party list, for a caller building a send audience.
     *
     * <p>Returns opaque identifiers only — never names, addresses, or contact details — so
     * exposing it does not turn audience building into a bulk PII export. {@link #resolve}
     * remains the endpoint for human preview, because a marketer wants counts, not ids.
     */
    @NonNull
    List<UUID> resolvePartyIds(@NonNull UUID segmentId);
}
