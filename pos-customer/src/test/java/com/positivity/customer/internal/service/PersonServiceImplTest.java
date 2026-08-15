package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CreatePersonResponse;
import com.positivity.customer.internal.dto.GetPersonResponse;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PartyRelationship;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.ContactPointType;
import com.positivity.customer.internal.enums.PreferredContactMethod;
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
 * CRM person records (#111, #684): pos-customer keeps only the thin link to the canonical
 * person, while names and contact points are resolved from pos-people (ADR-0015 I2). An invalid
 * email must persist nothing anywhere, including in pos-people.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PersonServiceImpl")
class PersonServiceImplTest {

    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3a01");
    private static final UUID PERSON_PARTY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3a02");
    private static final UUID ACCOUNT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3a03");
    private static final UUID USER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3a04");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    @Mock
    private PersonPartyRepository personRepository;

    @Mock
    private PartyRelationshipRepository partyRelationshipRepository;

    @Mock
    private PersonDirectoryService personDirectoryService;

    @Mock
    private CustomerFactPublisher customerFactPublisher;

    private PersonServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PersonServiceImpl(
                personRepository,
                partyRelationshipRepository,
                personDirectoryService,
                Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC),
                customerFactPublisher);
        when(personDirectoryService.resolveOrCreatePersonId(any(), any(), any(), any()))
                .thenReturn(PERSON_ID);
        when(personRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.empty());
        when(personRepository.save(any())).thenAnswer(invocation -> {
            PersonParty person = invocation.getArgument(0);
            person.setPartyId(PERSON_PARTY_ID);
            return person;
        });
        when(partyRelationshipRepository.findActiveByToPersonPartyId(any(), any()))
                .thenReturn(List.of());
        when(personDirectoryService.fetchPersonIdentitiesQuietly(any())).thenReturn(Map.of());
    }

    private static PersonParty personParty() {
        PersonParty person = new PersonParty();
        person.setPartyId(PERSON_PARTY_ID);
        person.setPersonId(PERSON_ID);
        person.setPreferredContactMethod(PreferredContactMethod.EMAIL);
        return person;
    }

    private static CreatePersonRequest.CreatePersonRequestBuilder request() {
        return CreatePersonRequest.builder()
                .firstName(" Jane ")
                .lastName(" Smith ")
                .preferredContactMethod(PreferredContactMethod.EMAIL);
    }

    private static PersonDirectoryService.PersonIdentity identity(
            List<PersonDirectoryService.ContactPoint> contactPoints) {
        return new PersonDirectoryService.PersonIdentity(
                PERSON_ID, "Jane", "Smith", "jane@example.invalid", contactPoints);
    }

    @Nested
    @DisplayName("createPerson")
    class CreatePerson {

        @Test
        void linksTheCanonicalPersonAndWritesContactPointsToPosPeople() {
            CreatePersonResponse response = service.createPerson(
                    request()
                            .emails(List.of(CreatePersonRequest.EmailInput.builder()
                                    .value("Jane@Example.Invalid")
                                    .isPrimary(true)
                                    .build()))
                            .phones(List.of(CreatePersonRequest.PhoneInput.builder()
                                    .value(" 555-0100 ")
                                    .isPrimary(true)
                                    .build()))
                            .build(),
                    USER_ID);

            ArgumentCaptor<PersonParty> saved = ArgumentCaptor.forClass(PersonParty.class);
            verify(personRepository).save(saved.capture());
            assertThat(saved.getValue().getPersonId()).isEqualTo(PERSON_ID);
            assertThat(saved.getValue().getCustomerNumber()).startsWith("CUST-PER-");
            verify(customerFactPublisher).partyChanged(saved.getValue());

            ArgumentCaptor<List<PersonDirectoryService.ContactPointUpsert>> contactPoints = ArgumentCaptor.captor();
            verify(personDirectoryService)
                    .setContactPoints(org.mockito.ArgumentMatchers.eq(PERSON_ID), contactPoints.capture());
            assertThat(contactPoints.getValue()).hasSize(2);
            // Emails are lower-cased before they leave pos-customer.
            assertThat(contactPoints.getValue().get(0).value()).isEqualTo("jane@example.invalid");
            assertThat(contactPoints.getValue().get(0).contactType()).isEqualTo(ContactPointType.EMAIL.name());
            assertThat(contactPoints.getValue().get(1).value()).isEqualTo("555-0100");
            // A phone with no explicit type defaults to mobile.
            assertThat(contactPoints.getValue().get(1).contactType()).isEqualTo(ContactPointType.PHONE_MOBILE.name());
            assertThat(response.getPersonId()).isEqualTo(PERSON_ID);
        }

        @Test
        void reusesAnExistingPersonPartyForTheSameCanonicalPerson() {
            when(personRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(personParty()));

            CreatePersonResponse response = service.createPerson(request().build(), USER_ID);

            assertThat(response.getPersonId()).isEqualTo(PERSON_ID);
            verify(personRepository, never()).save(any());
            verify(personDirectoryService, never()).setContactPoints(any(), any());
        }

        @Test
        void keepsAnExplicitPhoneType() {
            service.createPerson(
                    request()
                            .phones(List.of(CreatePersonRequest.PhoneInput.builder()
                                    .value("555-0100")
                                    .type(ContactPointType.PHONE_WORK)
                                    .build()))
                            .build(),
                    USER_ID);

            ArgumentCaptor<List<PersonDirectoryService.ContactPointUpsert>> contactPoints = ArgumentCaptor.captor();
            verify(personDirectoryService).setContactPoints(any(), contactPoints.capture());
            assertThat(contactPoints.getValue().get(0).contactType()).isEqualTo(ContactPointType.PHONE_WORK.name());
        }

        @Test
        void resolvesTheCanonicalPersonByTheFlaggedPrimaryContacts() {
            service.createPerson(
                    request()
                            .emails(List.of(
                                    CreatePersonRequest.EmailInput.builder()
                                            .value("secondary@example.invalid")
                                            .build(),
                                    CreatePersonRequest.EmailInput.builder()
                                            .value("primary@example.invalid")
                                            .isPrimary(true)
                                            .build()))
                            .phones(List.of(
                                    CreatePersonRequest.PhoneInput.builder()
                                            .value("555-0199")
                                            .build(),
                                    CreatePersonRequest.PhoneInput.builder()
                                            .value("555-0100")
                                            .isPrimary(true)
                                            .build()))
                            .build(),
                    USER_ID);

            verify(personDirectoryService)
                    .resolveOrCreatePersonId("primary@example.invalid", "555-0100", "Smith", "Jane");
        }

        @Test
        void fallsBackToTheFirstContactWhenNoneIsFlaggedPrimary() {
            service.createPerson(
                    request()
                            .emails(List.of(CreatePersonRequest.EmailInput.builder()
                                    .value("first@example.invalid")
                                    .build()))
                            .phones(List.of(CreatePersonRequest.PhoneInput.builder()
                                    .value("555-0199")
                                    .build()))
                            .build(),
                    USER_ID);

            verify(personDirectoryService)
                    .resolveOrCreatePersonId("first@example.invalid", "555-0199", "Smith", "Jane");
        }

        @Test
        void resolvesOnNameAloneWhenNoContactsAreSupplied() {
            service.createPerson(request().build(), USER_ID);

            verify(personDirectoryService).resolveOrCreatePersonId(null, null, "Smith", "Jane");
        }

        @Test
        void persistsNothingAnywhereWhenAnEmailIsMalformed() {
            CreatePersonRequest request = request()
                    .emails(List.of(CreatePersonRequest.EmailInput.builder()
                            .value("not-an-email")
                            .build()))
                    .build();

            assertThatThrownBy(() -> service.createPerson(request, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Invalid email format");
            verifyNoInteractions(personRepository, customerFactPublisher);
            verify(personDirectoryService, never()).resolveOrCreatePersonId(any(), any(), any(), any());
        }

        @Test
        void refusesABlankEmailValue() {
            CreatePersonRequest request = request()
                    .emails(List.of(
                            CreatePersonRequest.EmailInput.builder().value("  ").build()))
                    .build();

            assertThatThrownBy(() -> service.createPerson(request, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Email value is required");
        }

        @Test
        void refusesARequestWithNoFirstName() {
            CreatePersonRequest request = request().firstName("  ").build();

            assertThatThrownBy(() -> service.createPerson(request, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("firstName is required");
        }

        @Test
        void refusesARequestWithNoLastName() {
            CreatePersonRequest request = request().lastName(null).build();

            assertThatThrownBy(() -> service.createPerson(request, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("lastName is required");
        }

        @Test
        void refusesARequestWithNoPreferredContactMethod() {
            CreatePersonRequest request = request().preferredContactMethod(null).build();

            assertThatThrownBy(() -> service.createPerson(request, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("preferredContactMethod is required");
        }
    }

    @Nested
    @DisplayName("getPerson")
    class GetPerson {

        @Test
        void joinsTheCrmLinkToTheCanonicalIdentityAndItsCommercialAccounts() {
            when(personRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(personParty()));
            when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(PERSON_ID)))
                    .thenReturn(Map.of(
                            PERSON_ID,
                            identity(List.of(
                                    new PersonDirectoryService.ContactPoint("EMAIL", "jane@example.invalid", true)))));
            PartyRelationship relationship = new PartyRelationship();
            CommercialParty account = new CommercialParty();
            account.setPartyId(ACCOUNT_ID);
            relationship.setFromParty(account);
            when(partyRelationshipRepository.findActiveByToPersonPartyId(PERSON_PARTY_ID, TODAY))
                    .thenReturn(List.of(relationship, relationship));

            GetPersonResponse response = service.getPerson(PERSON_ID);

            assertThat(response.getFirstName()).isEqualTo("Jane");
            assertThat(response.getDisplayName()).isEqualTo("Jane Smith");
            assertThat(response.getContactPoints()).hasSize(1);
            assertThat(response.getContactPoints().get(0).getContactType()).isEqualTo(ContactPointType.EMAIL);
            assertThat(response.isCommercialContact()).isTrue();
            // Two relationships against the same account still count as one account.
            assertThat(response.getCommercialAccountCount()).isEqualTo(1);
        }

        @Test
        void servesTheLinkWithNoNamesWhenPosPeopleHasNothingForThePerson() {
            when(personRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(personParty()));

            GetPersonResponse response = service.getPerson(PERSON_ID);

            assertThat(response.getFirstName()).isNull();
            assertThat(response.getDisplayName()).isNull();
            assertThat(response.getContactPoints()).isEmpty();
            assertThat(response.isCommercialContact()).isFalse();
        }

        @Test
        void dropsAContactTypeThePosCustomerEnumDoesNotKnow() {
            when(personRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(personParty()));
            when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(PERSON_ID)))
                    .thenReturn(Map.of(
                            PERSON_ID,
                            identity(List.of(
                                    new PersonDirectoryService.ContactPoint("CARRIER_PIGEON", "coop 3", false),
                                    new PersonDirectoryService.ContactPoint(null, "unknown", false)))));

            GetPersonResponse response = service.getPerson(PERSON_ID);

            assertThat(response.getContactPoints())
                    .hasSize(2)
                    .allSatisfy(cp -> assertThat(cp.getContactType()).isNull());
        }

        @Test
        void failsForAPersonWithNoCrmLink() {
            when(personRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPerson(PERSON_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Person not found");
        }
    }

    @Nested
    @DisplayName("searchPersons")
    class SearchPersons {

        @Test
        void keepsOnlyDirectoryMatchesThatHaveACrmLink() {
            when(personDirectoryService.searchPersons("smith"))
                    .thenReturn(List.of(
                            identity(List.of()),
                            new PersonDirectoryService.PersonIdentity(
                                    UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3aff"),
                                    "Rob",
                                    "Smith",
                                    null,
                                    List.of())));
            when(personRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(personParty()));

            List<GetPersonResponse> matches = service.searchPersons("smith", null, null, 20, 0);

            assertThat(matches).hasSize(1);
            assertThat(matches.get(0).getPersonId()).isEqualTo(PERSON_ID);
        }

        @Test
        void searchesOnTheFirstNonBlankCriterion() {
            when(personDirectoryService.searchPersons("jane@example.invalid")).thenReturn(List.of());

            service.searchPersons("  ", "jane@example.invalid", "555-0100", 20, 0);

            verify(personDirectoryService).searchPersons("jane@example.invalid");
        }

        @Test
        void returnsNothingWhenNoCriterionIsSupplied() {
            assertThat(service.searchPersons(null, null, "  ", 20, 0)).isEmpty();
            verify(personDirectoryService, never()).searchPersons(any());
        }

        @Test
        void appliesTheRequestedPageWindow() {
            when(personDirectoryService.searchPersons("smith")).thenReturn(List.of(identity(List.of())));
            when(personRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(personParty()));

            assertThat(service.searchPersons("smith", null, null, 20, 1)).isEmpty();
            assertThat(service.searchPersons("smith", null, null, 1, 0)).hasSize(1);
        }

        @Test
        void defaultsTheLegacySingleTermSearchToTheFirstTwentyMatches() {
            when(personDirectoryService.searchPersons("smith")).thenReturn(List.of());

            assertThat(service.searchPersons("smith")).isEmpty();
            verify(personDirectoryService).searchPersons("smith");
        }
    }
}
