package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.config.OutboxEventWriter;
import com.positivity.customer.internal.config.SuppressionService;
import com.positivity.customer.internal.dto.AssignPartyTagRequest;
import com.positivity.customer.internal.dto.CreateFollowUpTaskRequest;
import com.positivity.customer.internal.dto.CreatePartyRelationshipRequest;
import com.positivity.customer.internal.dto.CreateVehicleForPartyRequest;
import com.positivity.customer.internal.dto.CustomerDTO;
import com.positivity.customer.internal.dto.MergePartiesRequest;
import com.positivity.customer.internal.dto.RecordInteractionRequest;
import com.positivity.customer.internal.dto.SegmentMembersRequest;
import com.positivity.customer.internal.dto.UpdateContactRolesRequest;
import com.positivity.customer.internal.dto.UpdateMarketingConsentRequest;
import com.positivity.customer.internal.dto.UpsertBillingRulesRequest;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesRequest;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PartyRelationship;
import com.positivity.customer.internal.entity.Segment;
import com.positivity.customer.internal.exception.HouseAccountImmutableException;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import com.positivity.customer.internal.repository.ConsentEventRepository;
import com.positivity.customer.internal.repository.ContactRoleAssignmentRepository;
import com.positivity.customer.internal.repository.CustomerInteractionRepository;
import com.positivity.customer.internal.repository.ExtVehicleRepository;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PartyTagAssignmentRepository;
import com.positivity.customer.internal.repository.PartyTagRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.repository.SegmentMemberRepository;
import com.positivity.customer.internal.repository.SegmentRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Every pos-customer write that would change, merge, delete or attach data to a party refuses the
 * tenant's CASH house account (CAP:550 S7, #2505 rule 6): one case per guarded write path, each
 * driven through the service method the controller calls, with the real {@link HouseAccountGuard}.
 *
 * <p>The refusal has to come <em>before</em> any change and before any fact is queued, so each case
 * also asserts that no repository saw a write and that neither the fact publisher nor the outbox
 * was touched.
 */
@DisplayName("House account guard on every guarded write path (CAP:550 S7)")
class HouseAccountGuardedWritesTest {

    private static final UUID HOUSE = UUID.fromString("01980a58-0000-7000-8000-0000000000c1");
    private static final UUID ORDINARY = UUID.fromString("01980a58-0000-7000-8000-0000000000c2");
    private static final UUID OTHER_ID = UUID.fromString("01980a58-0000-7000-8000-0000000000c3");
    private static final UUID RELATIONSHIP_ID = UUID.fromString("01980a58-0000-7000-8000-0000000000c4");
    private static final UUID SEGMENT_ID = UUID.fromString("01980a58-0000-7000-8000-0000000000c5");

    /** Every service a guarded endpoint delegates to, built over mocks and the real guard. */
    static final class Fixture {
        final Clock clock = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);
        final CommercialPartyRepository commercialParties = mock(CommercialPartyRepository.class);
        final PersonPartyRepository personParties = mock(PersonPartyRepository.class);
        final PartyRelationshipRepository relationships = mock(PartyRelationshipRepository.class);
        final CommunicationPreferenceRepository preferences = mock(CommunicationPreferenceRepository.class);
        final ConsentEventRepository consentEvents = mock(ConsentEventRepository.class);
        final ContactRoleAssignmentRepository contactRoles = mock(ContactRoleAssignmentRepository.class);
        final FollowUpTaskRepository followUps = mock(FollowUpTaskRepository.class);
        final CustomerInteractionRepository interactions = mock(CustomerInteractionRepository.class);
        final PartyTagRepository tags = mock(PartyTagRepository.class);
        final PartyTagAssignmentRepository tagAssignments = mock(PartyTagAssignmentRepository.class);
        final SegmentRepository segments = mock(SegmentRepository.class);
        final SegmentMemberRepository segmentMembers = mock(SegmentMemberRepository.class);
        final CustomerFactPublisher factPublisher = mock(CustomerFactPublisher.class);

        @SuppressWarnings("unchecked")
        final ObjectProvider<OutboxEventWriter> outbox = mock(ObjectProvider.class);

        final PersonDirectoryService personDirectory = mock(PersonDirectoryService.class);
        final HouseAccountGuard guard = new HouseAccountGuard(commercialParties);

        final PartyServiceImpl party = new PartyServiceImpl(
                clock,
                commercialParties,
                personParties,
                relationships,
                mock(CacheManager.class),
                personDirectory,
                mock(ExtVehicleRepository.class),
                outbox,
                factPublisher,
                mock(MarketingConsentService.class),
                mock(CustomerInteractionService.class),
                mock(EntityManager.class),
                guard);
        final CommercialPartyServiceImpl commercial =
                new CommercialPartyServiceImpl(commercialParties, factPublisher, guard);
        final PersonPartyServiceImpl person =
                new PersonPartyServiceImpl(personParties, personDirectory, factPublisher, guard);
        final CommunicationPreferenceServiceImpl communicationPreferences =
                new CommunicationPreferenceServiceImpl(preferences, commercialParties, personParties, clock, guard);
        final MarketingConsentServiceImpl consent = new MarketingConsentServiceImpl(
                clock,
                mock(MarketingConsentResolver.class),
                mock(SuppressionService.class),
                preferences,
                commercialParties,
                personParties,
                consentEvents,
                factPublisher,
                guard);
        final ContactRoleServiceImpl contacts =
                new ContactRoleServiceImpl(contactRoles, commercialParties, personParties, personDirectory, guard);
        final FollowUpTaskServiceImpl followUp =
                new FollowUpTaskServiceImpl(clock, followUps, commercialParties, personParties, guard);
        final CustomerInteractionServiceImpl interaction =
                new CustomerInteractionServiceImpl(clock, interactions, guard);
        final PartyRelationshipServiceImpl relationship = new PartyRelationshipServiceImpl(
                relationships, commercialParties, personParties, personDirectory, clock, factPublisher, guard);
        final PartyTagServiceImpl tag = new PartyTagServiceImpl(clock, tags, tagAssignments, factPublisher, guard);
        final SegmentServiceImpl segment = new SegmentServiceImpl(
                clock,
                new ObjectMapper(),
                segments,
                segmentMembers,
                mock(SegmentResolutionService.class),
                mock(MarketingConsentService.class),
                factPublisher,
                guard);

        Fixture() {
            when(commercialParties.existsByPartyIdAndHouseAccountIsNotNull(HOUSE))
                    .thenReturn(true);
            // Merge loads the survivor before it reads the loser from the body.
            CommercialParty ordinary = new CommercialParty();
            ordinary.setPartyId(ORDINARY);
            when(commercialParties.findByPartyId(ORDINARY)).thenReturn(ordinary);
            // deactivateRelationship takes no party id: the relationship's own account is the target.
            CommercialParty houseAccount = new CommercialParty();
            houseAccount.setPartyId(HOUSE);
            PartyRelationship ofHouseAccount = mock(PartyRelationship.class);
            when(ofHouseAccount.getFromParty()).thenReturn(houseAccount);
            when(relationships.findById(RELATIONSHIP_ID)).thenReturn(Optional.of(ofHouseAccount));
            when(segments.findById(SEGMENT_ID)).thenReturn(Optional.of(mock(Segment.class)));
        }

        List<Object> repositories() {
            return List.of(
                    commercialParties,
                    personParties,
                    relationships,
                    preferences,
                    consentEvents,
                    contactRoles,
                    followUps,
                    interactions,
                    tags,
                    tagAssignments,
                    segments,
                    segmentMembers);
        }
    }

    private static Named<Consumer<Fixture>> path(String name, Consumer<Fixture> call) {
        return Named.of(name, call);
    }

    private static MergePartiesRequest merge(UUID losingPartyId) {
        return MergePartiesRequest.builder()
                .losingPartyId(losingPartyId.toString())
                .justification("duplicate")
                .build();
    }

    static Stream<Named<Consumer<Fixture>>> guardedWrites() {
        return Stream.of(
                path("PUT /v1/crm/{id} (commercial)", f -> f.commercial.updateCustomer(HOUSE, new CustomerDTO())),
                path("PUT /v1/crm/{id} (person store named)", f -> f.person.updateCustomer(HOUSE, new CustomerDTO())),
                path("DELETE /v1/crm/{id}", f -> f.commercial.deleteCustomer(HOUSE)),
                path("POST …/parties/{partyId}/merge as survivor", f -> f.party.mergeParties(HOUSE, merge(ORDINARY))),
                path("POST …/parties/{partyId}/merge as loser", f -> f.party.mergeParties(ORDINARY, merge(HOUSE))),
                path(
                        "POST /v1/crm/accounts/parties/{partyId}/communicationPreferences",
                        f -> f.party.upsertCommunicationPreferences(
                                HOUSE, new UpsertCommunicationPreferencesRequest())),
                path(
                        "POST /v1/crm/accounts/parties/{partyId}/vehicles",
                        f -> f.party.createVehicleForParty(
                                HOUSE,
                                CreateVehicleForPartyRequest.builder()
                                        .vinNumber("1HGCM82633A004352")
                                        .build())),
                path(
                        "PUT /v1/crm/accounts/parties/{partyId}/billing-rules",
                        f -> f.party.upsertBillingRulesForParty(HOUSE, new UpsertBillingRulesRequest())),
                path(
                        "POST /v1/crm/parties/{partyId}/communicationPreferences",
                        f -> f.communicationPreferences.upsertCommunicationPreferences(
                                HOUSE, new UpsertCommunicationPreferencesRequest())),
                path(
                        "PUT /v1/crm/parties/{partyId}/marketing-consent",
                        f -> f.consent.updateConsent(HOUSE, new UpdateMarketingConsentRequest())),
                path(
                        "PUT /v1/crm/parties/{partyId}/marketing-consent/account-gate",
                        f -> f.consent.setAccountMarketingOptOut(HOUSE, false)),
                path(
                        "PUT /v1/crm/parties/{partyId}/contacts/{contactId}/roles",
                        f -> f.contacts.updateContactRoles(HOUSE, OTHER_ID, new UpdateContactRolesRequest())),
                path(
                        "POST /v1/crm/parties/{partyId}/follow-ups",
                        f -> f.followUp.create(HOUSE, new CreateFollowUpTaskRequest())),
                path(
                        "POST /v1/crm/parties/{partyId}/interactions",
                        f -> f.interaction.record(HOUSE, new RecordInteractionRequest())),
                path(
                        "POST /v1/crm/commercial-accounts/{partyId}/relationships",
                        f -> f.relationship.createRelationship(HOUSE, new CreatePartyRelationshipRequest(), OTHER_ID)),
                path(
                        "PUT /v1/crm/commercial-accounts/{partyId}/relationships/{relationshipId}/primary-billing",
                        f -> f.relationship.designatePrimaryBillingContact(HOUSE, RELATIONSHIP_ID, OTHER_ID)),
                path(
                        "DELETE /v1/crm/commercial-accounts/{partyId}/relationships/{relationshipId}",
                        f -> f.relationship.deactivateRelationship(RELATIONSHIP_ID, OTHER_ID)),
                path("POST /v1/crm/parties/{partyId}/tags", f -> f.tag.assignTag(HOUSE, new AssignPartyTagRequest())),
                path("DELETE /v1/crm/parties/{partyId}/tags/{tagId}", f -> f.tag.removeTag(HOUSE, OTHER_ID)),
                path(
                        "POST /v1/crm/segments/{segmentId}/members",
                        f -> f.segment.addMembers(SEGMENT_ID, new SegmentMembersRequest(List.of(ORDINARY, HOUSE)))),
                path(
                        "DELETE /v1/crm/segments/{segmentId}/members/{partyId}",
                        f -> f.segment.removeMember(SEGMENT_ID, HOUSE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("guardedWrites")
    @DisplayName("refuses the house account before any change and before any fact is queued")
    void refusesTheHouseAccount(Consumer<Fixture> guardedWrite) {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> guardedWrite.accept(fixture))
                .isInstanceOfSatisfying(
                        HouseAccountImmutableException.class,
                        ex -> assertThat(ex.getPartyId()).isEqualTo(HOUSE));

        for (Object repository : fixture.repositories()) {
            assertThat(mockingDetails(repository).getInvocations())
                    .extracting(invocation -> invocation.getMethod().getName())
                    .as(
                            "no write reached %s",
                            mockingDetails(repository).getMockCreationSettings().getTypeToMock())
                    .noneMatch(HouseAccountGuardedWritesTest::isWrite);
        }
        verifyNoInteractions(fixture.factPublisher, fixture.outbox);
    }

    private static boolean isWrite(String repositoryMethod) {
        return repositoryMethod.startsWith("save")
                || repositoryMethod.startsWith("delete")
                || repositoryMethod.startsWith("reassign")
                || repositoryMethod.startsWith("demote")
                || repositoryMethod.startsWith("update");
    }
}
