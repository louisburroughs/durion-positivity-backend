package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.CreatePartyRelationshipRequest;
import com.positivity.customer.internal.dto.CreatePartyRelationshipResponse;
import com.positivity.customer.internal.dto.GetCommercialAccountContactsResponse;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PartyRelationship;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.PartyRelationshipRole;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

/**
 * Party relationships between a commercial account and individuals (#110): at most one primary
 * billing contact at a time, no overlapping active relationship for the same pair and role, and
 * identity details read from pos-people rather than stored locally (ADR-0015 I2).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PartyRelationshipServiceImpl")
class PartyRelationshipServiceImplTest {

    private static final UUID PARTY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f2a01");
    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f2a02");
    private static final UUID PERSON_PARTY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f2a03");
    private static final UUID RELATIONSHIP_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f2a04");
    private static final UUID USER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f2a05");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    @Mock
    private PartyRelationshipRepository partyRelationshipRepository;

    @Mock
    private CommercialPartyRepository partyRepository;

    @Mock
    private PersonPartyRepository personRepository;

    @Mock
    private PersonDirectoryService personDirectoryService;

    @Mock
    private CustomerFactPublisher customerFactPublisher;

    private PartyRelationshipServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PartyRelationshipServiceImpl(
                partyRelationshipRepository,
                partyRepository,
                personRepository,
                personDirectoryService,
                Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC),
                customerFactPublisher);
        when(partyRepository.findById(PARTY_ID)).thenReturn(Optional.of(commercialParty()));
        when(personRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(personParty()));
        when(partyRelationshipRepository.findOverlappingRelationships(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(partyRelationshipRepository.save(any())).thenAnswer(invocation -> {
            PartyRelationship relationship = invocation.getArgument(0);
            if (relationship.getPartyRelationshipId() == null) {
                relationship.setPartyRelationshipId(RELATIONSHIP_ID);
            }
            return relationship;
        });
        when(personDirectoryService.fetchPersonIdentitiesQuietly(any())).thenReturn(Map.of());
    }

    private static CommercialParty commercialParty() {
        CommercialParty party = new CommercialParty();
        party.setPartyId(PARTY_ID);
        return party;
    }

    private static PersonParty personParty() {
        PersonParty person = new PersonParty();
        person.setPartyId(PERSON_PARTY_ID);
        person.setPersonId(PERSON_ID);
        return person;
    }

    private static PartyRelationship relationship(Set<PartyRelationshipRole> roles, LocalDate endDate) {
        PartyRelationship relationship = new PartyRelationship();
        relationship.setPartyRelationshipId(RELATIONSHIP_ID);
        relationship.setFromParty(commercialParty());
        relationship.setToPerson(personParty());
        relationship.setRoles(roles);
        relationship.setEffectiveStartDate(LocalDate.of(2026, 1, 1));
        relationship.setEffectiveEndDate(endDate);
        return relationship;
    }

    private static CreatePartyRelationshipRequest createRequest(
            Set<PartyRelationshipRole> roles, boolean primaryBilling) {
        return CreatePartyRelationshipRequest.builder()
                .personId(PERSON_ID)
                .roles(roles)
                .isPrimaryBillingContact(primaryBilling)
                .effectiveStartDate(LocalDate.of(2026, 1, 1))
                .build();
    }

    @Nested
    @DisplayName("createRelationship")
    class CreateRelationship {

        @Test
        void createsAPlainRelationshipAndRepublishesThePersonsIdentity() {
            CreatePartyRelationshipResponse response = service.createRelationship(
                    PARTY_ID, createRequest(Set.of(PartyRelationshipRole.APPROVER), false), USER_ID);

            ArgumentCaptor<PartyRelationship> saved = ArgumentCaptor.forClass(PartyRelationship.class);
            verify(partyRelationshipRepository).save(saved.capture());
            assertThat(saved.getValue().getFromParty().getPartyId()).isEqualTo(PARTY_ID);
            assertThat(saved.getValue().getToPerson().getPersonId()).isEqualTo(PERSON_ID);
            assertThat(saved.getValue().getCreatedBy()).isEqualTo(USER_ID);
            assertThat(response.getRelationshipId()).isEqualTo(RELATIONSHIP_ID);
            assertThat(response.isPreviousPrimaryDemoted()).isFalse();
            verify(customerFactPublisher).personIdentityChanged(PERSON_ID);
            verify(partyRelationshipRepository, never()).demoteExistingPrimaryBillingContact(any(), any());
        }

        @Test
        void demotesTheExistingPrimaryWhenANewOneIsDesignated() {
            when(partyRelationshipRepository.demoteExistingPrimaryBillingContact(PARTY_ID, RELATIONSHIP_ID))
                    .thenReturn(1);

            CreatePartyRelationshipResponse response = service.createRelationship(
                    PARTY_ID, createRequest(Set.of(PartyRelationshipRole.BILLING), true), USER_ID);

            assertThat(response.isPrimaryBillingContact()).isTrue();
            assertThat(response.isPreviousPrimaryDemoted()).isTrue();
        }

        @Test
        void reportsNoDemotionWhenTheAccountHadNoPrimaryYet() {
            when(partyRelationshipRepository.demoteExistingPrimaryBillingContact(PARTY_ID, RELATIONSHIP_ID))
                    .thenReturn(0);

            assertThat(service.createRelationship(
                                    PARTY_ID, createRequest(Set.of(PartyRelationshipRole.BILLING), true), USER_ID)
                            .isPreviousPrimaryDemoted())
                    .isFalse();
        }

        @Test
        void refusesAPrimaryBillingContactThatDoesNotHoldTheBillingRole() {
            CreatePartyRelationshipRequest request = createRequest(Set.of(PartyRelationshipRole.APPROVER), true);

            assertThatThrownBy(() -> service.createRelationship(PARTY_ID, request, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("must have BILLING role");
            verify(partyRelationshipRepository, never()).save(any());
        }

        @Test
        void refusesAnOverlappingRelationshipForTheSamePairAndRole() {
            when(partyRelationshipRepository.findOverlappingRelationships(
                            eq(PARTY_ID), eq(PERSON_PARTY_ID), eq(PartyRelationshipRole.BILLING), eq(TODAY)))
                    .thenReturn(List.of(relationship(Set.of(PartyRelationshipRole.BILLING), null)));
            CreatePartyRelationshipRequest request = createRequest(Set.of(PartyRelationshipRole.BILLING), false);

            assertThatThrownBy(() -> service.createRelationship(PARTY_ID, request, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Active relationship already exists");
        }

        @Test
        void failsWhenTheCommercialAccountDoesNotExist() {
            when(partyRepository.findById(PARTY_ID)).thenReturn(Optional.empty());
            CreatePartyRelationshipRequest request = createRequest(Set.of(PartyRelationshipRole.BILLING), false);

            assertThatThrownBy(() -> service.createRelationship(PARTY_ID, request, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Party not found");
        }

        @Test
        void failsWhenThePersonDoesNotExist() {
            when(personRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.empty());
            CreatePartyRelationshipRequest request = createRequest(Set.of(PartyRelationshipRole.BILLING), false);

            assertThatThrownBy(() -> service.createRelationship(PARTY_ID, request, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Person not found");
        }
    }

    @Nested
    @DisplayName("getContactsForCommercialAccount")
    class GetContactsForCommercialAccount {

        @Test
        void servesContactDetailsFromThePeopleDirectoryRatherThanLocalColumns() {
            when(partyRepository.existsById(PARTY_ID)).thenReturn(true);
            when(partyRelationshipRepository.findActiveByFromPartyId(PARTY_ID, TODAY))
                    .thenReturn(List.of(relationship(Set.of(PartyRelationshipRole.BILLING), null)));
            when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(PERSON_ID)))
                    .thenReturn(Map.of(
                            PERSON_ID,
                            new PersonDirectoryService.PersonIdentity(
                                    PERSON_ID, "Jane", "Smith", "jane@example.invalid", List.of())));

            GetCommercialAccountContactsResponse response =
                    service.getContactsForCommercialAccount(PARTY_ID, null, null);

            assertThat(response.getContacts()).hasSize(1);
            GetCommercialAccountContactsResponse.ContactWithRole contact =
                    response.getContacts().get(0);
            assertThat(contact.getIndividualId()).isEqualTo(PERSON_ID);
            assertThat(contact.getStatus()).isEqualTo("ACTIVE");
            assertThat(contact.getIndividual().getDisplayName()).isEqualTo("Jane Smith");
            assertThat(contact.getIndividual().getEmail()).isEqualTo("jane@example.invalid");
            assertThat(contact.getIndividual().getPhone()).isNull();
        }

        @Test
        void leavesTheIndividualBlankWhenTheDirectoryHasNothingForThem() {
            when(partyRepository.existsById(PARTY_ID)).thenReturn(true);
            when(partyRelationshipRepository.findActiveByFromPartyId(PARTY_ID, TODAY))
                    .thenReturn(List.of(relationship(Set.of(PartyRelationshipRole.BILLING), null)));

            GetCommercialAccountContactsResponse.ContactWithRole contact = service.getContactsForCommercialAccount(
                            PARTY_ID, null, null)
                    .getContacts()
                    .get(0);

            assertThat(contact.getIndividual().getDisplayName()).isNull();
            assertThat(contact.getIndividual().getEmail()).isNull();
        }

        @Test
        void readsEveryRelationshipWhenTheCallerAsksForMoreThanActiveOnes() {
            when(partyRepository.existsById(PARTY_ID)).thenReturn(true);
            when(partyRelationshipRepository.findByFromPartyPartyId(PARTY_ID))
                    .thenReturn(List.of(relationship(Set.of(PartyRelationshipRole.BILLING), LocalDate.of(2026, 2, 1))));

            GetCommercialAccountContactsResponse response =
                    service.getContactsForCommercialAccount(PARTY_ID, null, "INACTIVE");

            assertThat(response.getContacts()).hasSize(1);
            assertThat(response.getContacts().get(0).getStatus()).isEqualTo("INACTIVE");
        }

        @Test
        void filtersToTheRequestedRoles() {
            when(partyRepository.existsById(PARTY_ID)).thenReturn(true);
            when(partyRelationshipRepository.findActiveByFromPartyId(PARTY_ID, TODAY))
                    .thenReturn(List.of(
                            relationship(Set.of(PartyRelationshipRole.BILLING), null),
                            relationship(Set.of(PartyRelationshipRole.APPROVER), null)));

            GetCommercialAccountContactsResponse response = service.getContactsForCommercialAccount(
                    PARTY_ID, List.of(PartyRelationshipRole.APPROVER), "ACTIVE");

            assertThat(response.getContacts()).hasSize(1);
            assertThat(response.getContacts().get(0).getRoles()).containsExactly(PartyRelationshipRole.APPROVER);
        }

        @Test
        void acceptsAPersonPartyIdAsWellAsACommercialOne() {
            when(partyRepository.existsById(PARTY_ID)).thenReturn(false);
            when(personRepository.existsById(PARTY_ID)).thenReturn(true);
            when(partyRelationshipRepository.findActiveByFromPartyId(PARTY_ID, TODAY))
                    .thenReturn(List.of());

            assertThat(service.getContactsForCommercialAccount(PARTY_ID, null, null)
                            .getContacts())
                    .isEmpty();
        }

        @Test
        void failsWhenNoPartyOfEitherKindExists() {
            when(partyRepository.existsById(PARTY_ID)).thenReturn(false);
            when(personRepository.existsById(PARTY_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.getContactsForCommercialAccount(PARTY_ID, null, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Party not found");
        }
    }

    @Nested
    @DisplayName("deactivateRelationship")
    class DeactivateRelationship {

        @Test
        void endsTheRelationshipTodayAndRepublishesTheIdentity() {
            PartyRelationship existing = relationship(Set.of(PartyRelationshipRole.BILLING), null);
            when(partyRelationshipRepository.findById(RELATIONSHIP_ID)).thenReturn(Optional.of(existing));

            service.deactivateRelationship(RELATIONSHIP_ID, USER_ID);

            assertThat(existing.getEffectiveEndDate()).isEqualTo(TODAY);
            assertThat(existing.getUpdatedBy()).isEqualTo(USER_ID);
            verify(partyRelationshipRepository).save(existing);
            verify(customerFactPublisher).personIdentityChanged(PERSON_ID);
        }

        @Test
        void failsForAnUnknownRelationship() {
            when(partyRelationshipRepository.findById(RELATIONSHIP_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deactivateRelationship(RELATIONSHIP_ID, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Relationship not found");
        }
    }

    @Nested
    @DisplayName("designatePrimaryBillingContact")
    class DesignatePrimaryBillingContact {

        @Test
        void promotesTheRelationshipAndDemotesTheIncumbent() {
            PartyRelationship existing = relationship(Set.of(PartyRelationshipRole.BILLING), null);
            when(partyRelationshipRepository.findById(RELATIONSHIP_ID)).thenReturn(Optional.of(existing));

            service.designatePrimaryBillingContact(PARTY_ID, RELATIONSHIP_ID, USER_ID);

            assertThat(existing.isPrimaryBillingContact()).isTrue();
            assertThat(existing.getUpdatedBy()).isEqualTo(USER_ID);
            verify(partyRelationshipRepository).demoteExistingPrimaryBillingContact(PARTY_ID, RELATIONSHIP_ID);
        }

        @Test
        void refusesARelationshipBelongingToAnotherParty() {
            PartyRelationship foreign = relationship(Set.of(PartyRelationshipRole.BILLING), null);
            CommercialParty otherParty = new CommercialParty();
            otherParty.setPartyId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f2aff"));
            foreign.setFromParty(otherParty);
            when(partyRelationshipRepository.findById(RELATIONSHIP_ID)).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> service.designatePrimaryBillingContact(PARTY_ID, RELATIONSHIP_ID, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("does not belong to party");
        }

        @Test
        void refusesARelationshipWithoutTheBillingRole() {
            when(partyRelationshipRepository.findById(RELATIONSHIP_ID))
                    .thenReturn(Optional.of(relationship(Set.of(PartyRelationshipRole.APPROVER), null)));

            assertThatThrownBy(() -> service.designatePrimaryBillingContact(PARTY_ID, RELATIONSHIP_ID, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("must have BILLING role");
        }

        @Test
        void refusesARelationshipThatHasAlreadyEnded() {
            when(partyRelationshipRepository.findById(RELATIONSHIP_ID))
                    .thenReturn(
                            Optional.of(relationship(Set.of(PartyRelationshipRole.BILLING), LocalDate.of(2026, 2, 1))));

            assertThatThrownBy(() -> service.designatePrimaryBillingContact(PARTY_ID, RELATIONSHIP_ID, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Cannot designate inactive relationship");
        }

        @Test
        void failsForAnUnknownRelationship() {
            when(partyRelationshipRepository.findById(RELATIONSHIP_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.designatePrimaryBillingContact(PARTY_ID, RELATIONSHIP_ID, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Relationship not found");
        }
    }
}
