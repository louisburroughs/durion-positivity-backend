package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.domain.SegmentPredicate;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.entity.Segment;
import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.SegmentOperator;
import com.positivity.customer.internal.enums.SegmentType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import com.positivity.customer.internal.repository.ExtOrganizationPostalAddressRepository;
import com.positivity.customer.internal.repository.ExtPersonReplicaRepository;
import com.positivity.customer.internal.repository.ExtVehicleCarePreferenceRepository;
import com.positivity.customer.internal.repository.ExtVehicleRepository;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.PartyTagAssignmentRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.repository.SegmentMemberRepository;
import com.positivity.customer.internal.repository.ServiceHistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SegmentResolutionService}'s resolution entry points.
 *
 * <p>
 * Complements {@code SegmentResolutionServiceTest}, which covers the
 * service-due attribute helpers. This class covers {@code resolve} and the two
 * strategies behind it.
 *
 * <p>
 * The behaviour that matters is defensive. A STATIC segment's pinned members are
 * <em>still</em> filtered by audience type, because a party can be reclassified
 * or deleted after it was pinned, and sending to a party that no longer fits the
 * audience produces a message written for the wrong kind of customer. And a
 * dynamic resolution that hits the candidate ceiling reports itself as truncated
 * rather than returning a silently partial audience, so a marketer never believes
 * a segment is smaller than it is.
 *
 * <p>
 * The candidate loaders themselves ({@code loadCommercialCandidates} and
 * {@code loadIndividualCandidates}) assemble attributes across eleven
 * repositories; they are exercised by the module's integration tests rather than
 * reconstructed here from mocks.
 */
@DisplayName("SegmentResolutionService — resolution strategies")
class SegmentResolutionServiceResolveTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID SEGMENT_ID = UUID.randomUUID();

    private final CommercialPartyRepository commercialParties = mock(CommercialPartyRepository.class);
    private final PersonPartyRepository personParties = mock(PersonPartyRepository.class);
    private final CommunicationPreferenceRepository preferences = mock(CommunicationPreferenceRepository.class);
    private final ExtVehicleRepository vehicles = mock(ExtVehicleRepository.class);
    private final PartyTagAssignmentRepository tags = mock(PartyTagAssignmentRepository.class);
    private final SegmentMemberRepository segmentMembers = mock(SegmentMemberRepository.class);
    private final ServiceHistoryRepository serviceHistory = mock(ServiceHistoryRepository.class);
    private final FollowUpTaskRepository followUps = mock(FollowUpTaskRepository.class);
    private final ExtPersonReplicaRepository personReplicas = mock(ExtPersonReplicaRepository.class);
    private final ExtOrganizationPostalAddressRepository orgAddresses =
            mock(ExtOrganizationPostalAddressRepository.class);
    private final ExtVehicleCarePreferenceRepository carePreferences = mock(ExtVehicleCarePreferenceRepository.class);

    private SegmentResolutionService service;

    @BeforeEach
    void setUp() {
        service = new SegmentResolutionService(
                commercialParties,
                personParties,
                preferences,
                vehicles,
                tags,
                segmentMembers,
                serviceHistory,
                followUps,
                personReplicas,
                orgAddresses,
                carePreferences,
                TEST_CLOCK);
    }

    private static Segment segment(SegmentType type, AudienceType audienceType) {
        return Segment.builder()
                .segmentId(SEGMENT_ID)
                .name("A segment")
                .audienceType(audienceType)
                .type(type)
                .active(true)
                .build();
    }

    private static CommercialParty commercial(UUID partyId) {
        CommercialParty party = new CommercialParty();
        party.setPartyId(partyId);
        return party;
    }

    private static PersonParty person(UUID partyId) {
        PersonParty party = new PersonParty();
        party.setPartyId(partyId);
        return party;
    }

    @Nested
    @DisplayName("static segments")
    class StaticSegments {

        @Test
        @DisplayName("returns the pinned members that still match the audience, sorted and deduplicated")
        void resolvesPinnedMembers() {
            UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
            when(segmentMembers.findPartyIdsBySegmentId(SEGMENT_ID)).thenReturn(List.of(second, first, first));
            when(commercialParties.findAllById(any())).thenReturn(List.of(commercial(first), commercial(second)));

            SegmentResolutionService.Resolution resolution =
                    service.resolve(segment(SegmentType.STATIC, AudienceType.COMMERCIAL), Optional.empty());

            assertThat(resolution.partyIds()).containsExactly(first, second);
            assertThat(resolution.truncated()).isFalse();
        }

        @Test
        @DisplayName("drops a pinned member that no longer matches the audience type")
        void dropsMemberOutsideAudience() {
            UUID stillValid = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID reclassified = UUID.fromString("00000000-0000-0000-0000-000000000002");
            when(segmentMembers.findPartyIdsBySegmentId(SEGMENT_ID)).thenReturn(List.of(stillValid, reclassified));
            // Only one of the two is still a commercial party.
            when(commercialParties.findAllById(any())).thenReturn(List.of(commercial(stillValid)));

            SegmentResolutionService.Resolution resolution =
                    service.resolve(segment(SegmentType.STATIC, AudienceType.COMMERCIAL), Optional.empty());

            // Sending to a reclassified party would deliver a message written for
            // the wrong kind of customer.
            assertThat(resolution.partyIds()).containsExactly(stillValid);
        }

        @Test
        @DisplayName("checks an INDIVIDUAL audience against person parties, not commercial ones")
        void individualAudienceChecksPersonParties() {
            UUID partyId = UUID.randomUUID();
            when(segmentMembers.findPartyIdsBySegmentId(SEGMENT_ID)).thenReturn(List.of(partyId));
            when(personParties.findAllById(any())).thenReturn(List.of(person(partyId)));

            SegmentResolutionService.Resolution resolution =
                    service.resolve(segment(SegmentType.STATIC, AudienceType.INDIVIDUAL), Optional.empty());

            assertThat(resolution.partyIds()).containsExactly(partyId);
            verify(commercialParties, never()).findAllById(any());
        }

        @Test
        @DisplayName("returns empty without querying when the segment has no pinned members")
        void whenNoMembers_returnsEmpty() {
            when(segmentMembers.findPartyIdsBySegmentId(SEGMENT_ID)).thenReturn(List.of());

            SegmentResolutionService.Resolution resolution =
                    service.resolve(segment(SegmentType.STATIC, AudienceType.COMMERCIAL), Optional.empty());

            assertThat(resolution.partyIds()).isEmpty();
            verify(commercialParties, never()).findAllById(any());
        }

        @Test
        @DisplayName("ignores a predicate supplied alongside a static segment")
        void ignoresPredicateOnStaticSegment() {
            when(segmentMembers.findPartyIdsBySegmentId(SEGMENT_ID)).thenReturn(List.of());

            SegmentPredicate predicate =
                    new SegmentPredicate.Comparison("party.accountTier", SegmentOperator.IN, List.of("GOLD"));

            assertThat(service.resolve(segment(SegmentType.STATIC, AudienceType.COMMERCIAL), Optional.of(predicate))
                            .partyIds())
                    .isEmpty();
        }

        @Test
        @DisplayName("never reports a static resolution as truncated — the member list is bounded by definition")
        void staticResolutionIsNeverTruncated() {
            UUID partyId = UUID.randomUUID();
            when(segmentMembers.findPartyIdsBySegmentId(SEGMENT_ID)).thenReturn(List.of(partyId));
            when(commercialParties.findAllById(any())).thenReturn(List.of(commercial(partyId)));

            assertThat(service.resolve(segment(SegmentType.STATIC, AudienceType.COMMERCIAL), Optional.empty())
                            .truncated())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("dynamic segments")
    class DynamicSegments {

        @Test
        @DisplayName("fails loudly when a dynamic segment has no stored predicate")
        void whenPredicateMissing_throws() {
            // A dynamic segment without a predicate cannot compute membership; a
            // silent empty result would read as "nobody matches".
            assertThatIllegalStateException()
                    .isThrownBy(() ->
                            service.resolve(segment(SegmentType.DYNAMIC, AudienceType.COMMERCIAL), Optional.empty()))
                    .withMessageContaining(SEGMENT_ID.toString());
        }

        @Test
        @DisplayName("returns an empty, untruncated result when no candidates exist")
        void whenNoCandidates_returnsEmpty() {
            when(commercialParties.findAll()).thenReturn(List.of());

            SegmentPredicate predicate =
                    new SegmentPredicate.Comparison("party.accountTier", SegmentOperator.IN, List.of("GOLD"));

            SegmentResolutionService.Resolution resolution =
                    service.resolve(segment(SegmentType.DYNAMIC, AudienceType.COMMERCIAL), Optional.of(predicate));

            assertThat(resolution.partyIds()).isEmpty();
            assertThat(resolution.truncated()).isFalse();
        }
    }

    @Nested
    @DisplayName("loadAttributes")
    class LoadAttributes {

        @Test
        @DisplayName("returns empty without loading any candidates when asked for no parties")
        void whenNoPartyIds_returnsEmptyWithoutLoading() {
            assertThat(service.loadAttributes(AudienceType.COMMERCIAL, List.of()))
                    .isEmpty();

            verify(commercialParties, never()).findAll();
        }

        @Test
        @DisplayName("returns nothing when none of the requested parties are in the audience")
        void whenNoneMatch_returnsEmpty() {
            when(commercialParties.findAll()).thenReturn(List.of());

            assertThat(service.loadAttributes(AudienceType.COMMERCIAL, List.of(UUID.randomUUID())))
                    .isEmpty();
        }
    }
}
