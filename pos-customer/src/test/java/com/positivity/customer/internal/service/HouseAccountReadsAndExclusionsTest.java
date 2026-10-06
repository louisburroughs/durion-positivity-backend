package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.config.OutboxEventWriter;
import com.positivity.customer.internal.domain.PartyAttributes;
import com.positivity.customer.internal.dto.CustomerDTO;
import com.positivity.customer.internal.dto.DuplicateCheckResponse;
import com.positivity.customer.internal.dto.GetPartyResponse;
import com.positivity.customer.internal.dto.ResolveAccountTierRequest;
import com.positivity.customer.internal.dto.ResolveAccountTierResponse;
import com.positivity.customer.internal.dto.SearchPartiesRequest;
import com.positivity.customer.internal.dto.SearchPartiesResponse;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.entity.Segment;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.AccountTier;
import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.HouseAccountKind;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.enums.SegmentType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import com.positivity.customer.internal.repository.ExtOrganizationPostalAddressRepository;
import com.positivity.customer.internal.repository.ExtPersonReplicaRepository;
import com.positivity.customer.internal.repository.ExtVehicleCarePreferenceRepository;
import com.positivity.customer.internal.repository.ExtVehicleRepository;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PartyTagAssignmentRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.repository.SegmentMemberRepository;
import com.positivity.customer.internal.repository.ServiceHistoryRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;

/**
 * What the CASH house account looks like from outside the guard (CAP:550 S7, #2505): the flag on
 * the party fact and on the read responses, and its absence from pos-customer's own analytic
 * surfaces — segment candidates and static membership, the duplicate check, and tier resolution.
 */
@DisplayName("House account: fact flag, read responses and analytic exclusions (CAP:550 S7)")
class HouseAccountReadsAndExclusionsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID HOUSE = UUID.fromString("01980a58-0000-7000-8000-0000000000c1");
    private static final UUID ORDINARY = UUID.fromString("01980a58-0000-7000-8000-0000000000c2");

    private final CommercialPartyRepository commercialParties = mock(CommercialPartyRepository.class);
    private final PersonPartyRepository personParties = mock(PersonPartyRepository.class);
    private final PartyRelationshipRepository relationships = mock(PartyRelationshipRepository.class);
    private final CustomerFactPublisher factPublisher = mock(CustomerFactPublisher.class);

    private static CommercialParty houseAccount() {
        CommercialParty party = commercial(HOUSE, "Walk-in customer");
        party.setCustomerNumber("CASH");
        party.setHouseAccount(HouseAccountKind.CASH_SALE);
        party.setTierManualOverride(true);
        party.setAccountMarketingOptOut(true);
        return party;
    }

    private static CommercialParty commercial(UUID partyId, String legalName) {
        CommercialParty party = new CommercialParty();
        party.setPartyId(partyId);
        party.setLegalName(legalName);
        party.setDisplayName(legalName);
        party.setCustomerNumber("CUST-0000000A");
        party.setPartyType(PartyType.COMMERCIAL);
        party.setStatus(AccountStatus.ACTIVE);
        party.setTier(AccountTier.STANDARD);
        party.setVersion(3L);
        return party;
    }

    @Nested
    @DisplayName("customer.party.updated")
    class Fact {

        private final OutboxEventWriter writer = mock(OutboxEventWriter.class);
        private CustomerFactPublisher publisher;

        @BeforeEach
        void setUp() {
            @SuppressWarnings("unchecked")
            ObjectProvider<OutboxEventWriter> writerProvider = mock(ObjectProvider.class);
            when(writerProvider.getIfAvailable()).thenReturn(writer);
            publisher = new CustomerFactPublisher(
                    writerProvider,
                    CLOCK,
                    mock(PersonDirectoryService.class),
                    personParties,
                    relationships,
                    "customer.events.v1",
                    mock(EntityManager.class));
        }

        private CustomerPartyUpdatedV1 publishedPartyFact() {
            ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
            verify(writer).publish(eq("customer.events.v1"), captor.capture());
            assertThat(captor.getValue().eventType()).isEqualTo(CustomerPartyUpdatedV1.EVENT_TYPE);
            return (CustomerPartyUpdatedV1) captor.getValue().payload();
        }

        @Test
        @DisplayName("carries houseAccount = CASH_SALE for the house account")
        void houseAccountCarriesTheFlag() {
            publisher.partyChanged(houseAccount());

            CustomerPartyUpdatedV1 fact = publishedPartyFact();
            assertThat(fact.houseAccount()).isEqualTo(CustomerPartyUpdatedV1.HOUSE_ACCOUNT_CASH_SALE);
            assertThat(fact.partyId()).isEqualTo(HOUSE);
            assertThat(fact.customerNumber()).isEqualTo("CASH");
            assertThat(fact.status()).isEqualTo("ACTIVE");
            assertThat(fact.requirementsMet()).isTrue();
        }

        @Test
        @DisplayName("carries houseAccount = null for an ordinary commercial party")
        void ordinaryCommercialPartyHasNoFlag() {
            publisher.partyChanged(commercial(ORDINARY, "Acme Corp"));

            assertThat(publishedPartyFact().houseAccount()).isNull();
        }

        @Test
        @DisplayName("carries houseAccount = null for a person party")
        void personPartyHasNoFlag() {
            PersonParty person = new PersonParty();
            person.setPersonPartyId(ORDINARY);
            person.setCustomerNumber("CUST-PER-1");
            person.setStatus(AccountStatus.ACTIVE);

            publisher.partyChanged(person);

            assertThat(publishedPartyFact().houseAccount()).isNull();
        }
    }

    @Nested
    @DisplayName("read responses")
    class Reads {

        private PartyServiceImpl partyService;

        @BeforeEach
        void setUp() {
            @SuppressWarnings("unchecked")
            ObjectProvider<OutboxEventWriter> outbox = mock(ObjectProvider.class);
            partyService = new PartyServiceImpl(
                    CLOCK,
                    commercialParties,
                    personParties,
                    relationships,
                    mock(CacheManager.class),
                    mock(PersonDirectoryService.class),
                    mock(ExtVehicleRepository.class),
                    outbox,
                    factPublisher,
                    mock(MarketingConsentService.class),
                    mock(CustomerInteractionService.class),
                    mock(EntityManager.class),
                    mock(HouseAccountGuard.class));
        }

        @Test
        @DisplayName("GetPartyResponse names the house account and leaves an ordinary party null")
        void getParty() {
            when(commercialParties.findByPartyId(HOUSE)).thenReturn(houseAccount());
            when(commercialParties.findByPartyId(ORDINARY)).thenReturn(commercial(ORDINARY, "Acme Corp"));

            GetPartyResponse house = partyService.getParty(HOUSE);
            GetPartyResponse ordinary = partyService.getParty(ORDINARY);

            assertThat(house.getHouseAccount()).isEqualTo("CASH_SALE");
            assertThat(ordinary.getHouseAccount()).isNull();
        }

        @Test
        @DisplayName("search still returns the house account, flagged, beside unflagged parties")
        void searchParties() {
            when(commercialParties.findAll()).thenReturn(List.of(houseAccount(), commercial(ORDINARY, "Acme Corp")));

            SearchPartiesResponse response = partyService.searchParties(new SearchPartiesRequest());

            assertThat(response.getResults())
                    .extracting(
                            SearchPartiesResponse.PartySummary::getPartyId,
                            SearchPartiesResponse.PartySummary::getHouseAccount)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.api.Assertions.tuple(HOUSE.toString(), "CASH_SALE"),
                            org.assertj.core.api.Assertions.tuple(ORDINARY.toString(), null));
        }

        @Test
        @DisplayName("CustomerDTO names the house account and leaves an ordinary customer null")
        void customerDto() {
            CommercialPartyServiceImpl commercialService =
                    new CommercialPartyServiceImpl(commercialParties, factPublisher, mock(HouseAccountGuard.class));
            when(commercialParties.findById(HOUSE)).thenReturn(Optional.of(houseAccount()));
            when(commercialParties.findById(ORDINARY)).thenReturn(Optional.of(commercial(ORDINARY, "Acme Corp")));

            assertThat(commercialService.getCustomerById(HOUSE))
                    .map(CustomerDTO::getHouseAccount)
                    .contains("CASH_SALE");
            assertThat(commercialService.getCustomerById(ORDINARY).orElseThrow().getHouseAccount())
                    .isNull();
        }

        @Test
        @DisplayName("the duplicate check never offers the house account, even for its own name")
        void duplicateCheckIgnoresTheHouseAccount() {
            CommercialParty namesake = commercial(ORDINARY, "Walk-in customer");
            when(commercialParties.findByLegalNameContaining("Walk-in customer"))
                    .thenReturn(List.of(houseAccount(), namesake));

            DuplicateCheckResponse response = partyService.checkPartyDuplicates("Walk-in customer");

            // An ordinary party may share the name; it is the only match ever offered.
            assertThat(response.getExactMatchPartyId()).isEqualTo(ORDINARY.toString());
            assertThat(response.getPotentialDuplicates())
                    .extracting(DuplicateCheckResponse.PartyMatch::getPartyId)
                    .containsExactly(ORDINARY.toString());
        }

        @Test
        @DisplayName("the duplicate check reports nothing when only the house account carries the name")
        void duplicateCheckFindsNothingForTheHouseAccountAlone() {
            when(commercialParties.findByLegalNameContaining("Walk-in customer"))
                    .thenReturn(List.of(houseAccount()));

            DuplicateCheckResponse response = partyService.checkPartyDuplicates("Walk-in customer");

            assertThat(response.isDuplicatesFound()).isFalse();
            assertThat(response.getExactMatchPartyId()).isNull();
            assertThat(response.getPotentialDuplicates()).isEmpty();
        }
    }

    @Nested
    @DisplayName("tier resolution")
    class Tier {

        @Test
        @DisplayName("returns the stored tier for a house account without recalculating or applying")
        void houseAccountIsNeverReTiered() {
            AccountTierServiceImpl tierService = new AccountTierServiceImpl(CLOCK, commercialParties, factPublisher);
            CommercialParty house = houseAccount();
            when(commercialParties.findById(HOUSE)).thenReturn(Optional.of(house));

            // Revenue that would make any ordinary account ENTERPRISE, forced and applied.
            ResolveAccountTierResponse response = tierService.resolveAccountTier(ResolveAccountTierRequest.builder()
                    .accountId(HOUSE.toString())
                    .annualRevenue(new BigDecimal("5000000"))
                    .applyTier(true)
                    .forceRecalculation(true)
                    .build());

            assertThat(response.getCurrentTier()).isEqualTo(AccountTier.STANDARD);
            assertThat(response.getRecommendedTier()).isEqualTo(AccountTier.STANDARD);
            assertThat(response.isTierApplied()).isFalse();
            assertThat(response.isManualOverrideActive()).isTrue();
            assertThat(response.getResolutionReason()).isEqualTo(AccountTierServiceImpl.HOUSE_ACCOUNT_TIER_REASON);
            assertThat(house.getTier()).isEqualTo(AccountTier.STANDARD);
            verify(commercialParties, never()).save(any());
            verifyNoInteractions(factPublisher);
        }
    }

    @Nested
    @DisplayName("segments")
    class Segments {

        private final SegmentMemberRepository segmentMembers = mock(SegmentMemberRepository.class);
        private SegmentResolutionService resolution;

        @BeforeEach
        void setUp() {
            ExtVehicleCarePreferenceRepository carePreferences = mock(ExtVehicleCarePreferenceRepository.class);
            resolution = new SegmentResolutionService(
                    commercialParties,
                    personParties,
                    mock(CommunicationPreferenceRepository.class),
                    mock(ExtVehicleRepository.class),
                    mock(PartyTagAssignmentRepository.class),
                    segmentMembers,
                    mock(ServiceHistoryRepository.class),
                    mock(FollowUpTaskRepository.class),
                    mock(ExtPersonReplicaRepository.class),
                    mock(ExtOrganizationPostalAddressRepository.class),
                    carePreferences,
                    CLOCK);
            when(commercialParties.findAll()).thenReturn(List.of(houseAccount(), commercial(ORDINARY, "Acme Corp")));
        }

        @Test
        @DisplayName("the house account is never a dynamic-segment candidate")
        void notACandidate() {
            List<PartyAttributes> candidates = resolution.loadCandidates(AudienceType.COMMERCIAL);

            assertThat(candidates).extracting(PartyAttributes::partyId).containsExactly(ORDINARY);
        }

        @Test
        @DisplayName("attributes for a known party set leave the house account out")
        void notInLoadedAttributes() {
            List<PartyAttributes> attributes =
                    resolution.loadAttributes(AudienceType.COMMERCIAL, List.of(HOUSE, ORDINARY));

            assertThat(attributes).extracting(PartyAttributes::partyId).containsExactly(ORDINARY);
        }

        @Test
        @DisplayName("a static segment drops a pinned house account")
        void notAStaticMember() {
            UUID segmentId = UUID.fromString("01980a58-0000-7000-8000-0000000000c5");
            when(segmentMembers.findPartyIdsBySegmentId(segmentId)).thenReturn(List.of(HOUSE, ORDINARY));
            when(commercialParties.findAllById(any()))
                    .thenReturn(List.of(houseAccount(), commercial(ORDINARY, "Acme Corp")));
            Segment segment = Segment.builder()
                    .segmentId(segmentId)
                    .name("Everyone")
                    .audienceType(AudienceType.COMMERCIAL)
                    .type(SegmentType.STATIC)
                    .active(true)
                    .build();

            assertThat(resolution.resolve(segment, Optional.empty()).partyIds()).containsExactly(ORDINARY);
        }
    }
}
