package com.positivity.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CreatePersonResponse;
import com.positivity.customer.internal.dto.GetPersonResponse;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PartyRelationship;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.ContactPointType;
import com.positivity.customer.internal.enums.PreferredContactMethod;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.service.PersonDirectoryService;
import com.positivity.customer.service.PersonService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * Contract behavior integration tests for person management.
 * <p>
 * Tests acceptance criteria from Issue #111 (Create Individual PersonParty Record),
 * updated for issue #684: pos-people is the sole source of truth for person identity
 * and contact points (ADR-0015 I2), so {@link PersonDirectoryService} is mocked here and
 * pos-customer persists only the thin link.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PersonServiceContractBehaviorIT extends BaseContractIntegrationTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private PersonService personService;

    @Autowired
    private PersonPartyRepository personRepository;

    @Autowired
    private CommercialPartyRepository commercialPartyRepository;

    @Autowired
    private PartyRelationshipRepository partyRelationshipRepository;

    @MockitoBean
    private PersonDirectoryService personDirectoryService;

    @BeforeEach
    void setUp() {
        partyRelationshipRepository.deleteAll();
        commercialPartyRepository.deleteAll();
        personRepository.deleteAll();
    }

    private static PersonDirectoryService.PersonIdentity identity(
            UUID id, String first, String last, String... emails) {
        List<PersonDirectoryService.ContactPoint> cps = java.util.Arrays.stream(emails)
                .map(e -> new PersonDirectoryService.ContactPoint("EMAIL", e, true))
                .toList();
        return new PersonDirectoryService.PersonIdentity(id, first, last, emails.length > 0 ? emails[0] : null, cps);
    }

    // ==========================================================================
    // Issue #111: Create Individual PersonParty Record - Acceptance Criteria Tests
    // ==========================================================================

    /** AC1: Minimal create (name + preferred method) returns 201 and persists the thin link. */
    @Test
    @Order(1)
    void ac1_minimalCreate_persistsPerson() {
        UUID personId = UUID.randomUUID();
        when(personDirectoryService.resolveOrCreatePersonId(any(), any(), eq("Doe"), eq("John")))
                .thenReturn(personId);

        CreatePersonRequest request = CreatePersonRequest.builder()
                .firstName("John")
                .lastName("Doe")
                .preferredContactMethod(PreferredContactMethod.EMAIL)
                .build();

        CreatePersonResponse response = personService.createPerson(request, USER);

        assertThat(response.getPersonId()).isEqualTo(personId);
        assertThat(response.getFirstName()).isEqualTo("John");
        assertThat(response.getLastName()).isEqualTo("Doe");
        assertThat(response.getPreferredContactMethod()).isEqualTo(PreferredContactMethod.EMAIL);

        PersonParty savedPerson = personRepository.findByPersonId(personId).orElse(null);
        assertThat(savedPerson).isNotNull();
        assertThat(savedPerson.getPersonId()).isEqualTo(personId);
    }

    /** AC2: Create with two emails and two phones mirrors four contact points to pos-people. */
    @Test
    @Order(2)
    void ac2_createWithContactPoints_mirrorsAllContactPointsToPeople() {
        UUID personId = UUID.randomUUID();
        when(personDirectoryService.resolveOrCreatePersonId(any(), any(), any(), any()))
                .thenReturn(personId);

        CreatePersonRequest request = CreatePersonRequest.builder()
                .firstName("Jane")
                .lastName("Smith")
                .preferredContactMethod(PreferredContactMethod.PHONE_CALL)
                .emails(List.of(
                        CreatePersonRequest.EmailInput.builder()
                                .value("jane.primary@example.com")
                                .isPrimary(true)
                                .build(),
                        CreatePersonRequest.EmailInput.builder()
                                .value("jane.secondary@example.com")
                                .isPrimary(false)
                                .build()))
                .phones(List.of(
                        CreatePersonRequest.PhoneInput.builder()
                                .value("+1-555-123-4567")
                                .type(ContactPointType.PHONE_MOBILE)
                                .isPrimary(true)
                                .build(),
                        CreatePersonRequest.PhoneInput.builder()
                                .value("+1-555-987-6543")
                                .type(ContactPointType.PHONE_WORK)
                                .isPrimary(false)
                                .build()))
                .build();

        CreatePersonResponse response = personService.createPerson(request, USER);

        assertThat(response.getContactPointsCreated()).isEqualTo(4);
        // Contacts are written to pos-people (source of truth), not stored locally.
        verify(personDirectoryService).setContactPoints(eq(personId), argThat(list -> list.size() == 4));
    }

    /** AC3: Missing lastName returns 400 and persists nothing. */
    @Test
    @Order(3)
    void ac3_missingLastName_returns400AndPersistsNothing() {
        CreatePersonRequest request = CreatePersonRequest.builder()
                .firstName("John")
                .lastName(null)
                .preferredContactMethod(PreferredContactMethod.EMAIL)
                .build();

        assertThatThrownBy(() -> personService.createPerson(request, USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(
                                ((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(400));

        assertThat(personRepository.count()).isZero();
    }

    /** AC4: Invalid email format returns 400 and persists nothing. */
    @Test
    @Order(4)
    void ac4_invalidEmailFormat_returns400AndPersistsNothing() {
        CreatePersonRequest request = CreatePersonRequest.builder()
                .firstName("John")
                .lastName("Doe")
                .preferredContactMethod(PreferredContactMethod.EMAIL)
                .emails(List.of(CreatePersonRequest.EmailInput.builder()
                        .value("not-an-email")
                        .isPrimary(true)
                        .build()))
                .build();

        assertThatThrownBy(() -> personService.createPerson(request, USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(400);
                    assertThat(rse.getReason()).contains("Invalid email");
                });

        // Persists nothing locally AND never creates a canonical person in pos-people:
        // email validation runs before resolveOrCreatePersonId (issue #684 review).
        assertThat(personRepository.count()).isZero();
        verify(personDirectoryService, never()).resolveOrCreatePersonId(any(), any(), any(), any());
    }

    // ==========================================================================
    // Additional Tests for Issue #111
    // ==========================================================================

    @Test
    @Order(5)
    void getPerson_existingPerson_returnsPersonWithContactPointsFromPeople() {
        UUID personId = UUID.randomUUID();
        when(personDirectoryService.resolveOrCreatePersonId(any(), any(), any(), any()))
                .thenReturn(personId);

        CreatePersonRequest request = CreatePersonRequest.builder()
                .firstName("Test")
                .lastName("User")
                .preferredContactMethod(PreferredContactMethod.SMS)
                .emails(List.of(CreatePersonRequest.EmailInput.builder()
                        .value("test@example.com")
                        .isPrimary(true)
                        .build()))
                .build();
        CreatePersonResponse created = personService.createPerson(request, USER);

        when(personDirectoryService.fetchPersonIdentitiesQuietly(Set.of(personId)))
                .thenReturn(java.util.Map.of(personId, identity(personId, "Test", "User", "test@example.com")));

        GetPersonResponse response = personService.getPerson(created.getPersonId());

        assertThat(response.getPersonId()).isEqualTo(created.getPersonId());
        assertThat(response.getFirstName()).isEqualTo("Test");
        assertThat(response.getLastName()).isEqualTo("User");
        assertThat(response.getContactPoints()).hasSize(1);
        assertThat(response.getContactPoints().get(0).getValue()).isEqualTo("test@example.com");
    }

    @Test
    @Order(6)
    void getPerson_nonExistentPerson_returns404() {
        UUID nonExistentId = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

        assertThatThrownBy(() -> personService.getPerson(nonExistentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(
                                ((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(404));
    }

    @Test
    @Order(7)
    void searchPersons_byName_delegatesToPeopleAndReturnsLocalLinks() {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        UUID charlieId = UUID.randomUUID();
        when(personDirectoryService.resolveOrCreatePersonId(any(), any(), eq("Johnson"), eq("Alice")))
                .thenReturn(aliceId);
        when(personDirectoryService.resolveOrCreatePersonId(any(), any(), eq("Johnson"), eq("Bob")))
                .thenReturn(bobId);
        when(personDirectoryService.resolveOrCreatePersonId(any(), any(), eq("Brown"), eq("Charlie")))
                .thenReturn(charlieId);

        personService.createPerson(
                CreatePersonRequest.builder()
                        .firstName("Alice")
                        .lastName("Johnson")
                        .preferredContactMethod(PreferredContactMethod.EMAIL)
                        .build(),
                USER);
        personService.createPerson(
                CreatePersonRequest.builder()
                        .firstName("Bob")
                        .lastName("Johnson")
                        .preferredContactMethod(PreferredContactMethod.PHONE_CALL)
                        .build(),
                USER);
        personService.createPerson(
                CreatePersonRequest.builder()
                        .firstName("Charlie")
                        .lastName("Brown")
                        .preferredContactMethod(PreferredContactMethod.SMS)
                        .build(),
                USER);

        when(personDirectoryService.searchPersons("Johnson"))
                .thenReturn(List.of(identity(aliceId, "Alice", "Johnson"), identity(bobId, "Bob", "Johnson")));

        List<GetPersonResponse> results = personService.searchPersons("Johnson", null, null, 20, 0);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(p -> p.getLastName().equals("Johnson"));
    }

    @Test
    @Order(8)
    void emailNormalization_mirrorsLowercaseToPeople() {
        UUID personId = UUID.randomUUID();
        when(personDirectoryService.resolveOrCreatePersonId(any(), any(), any(), any()))
                .thenReturn(personId);

        CreatePersonRequest request = CreatePersonRequest.builder()
                .firstName("Test")
                .lastName("User")
                .preferredContactMethod(PreferredContactMethod.EMAIL)
                .emails(List.of(CreatePersonRequest.EmailInput.builder()
                        .value("Test.User@EXAMPLE.COM")
                        .isPrimary(true)
                        .build()))
                .build();

        personService.createPerson(request, USER);

        verify(personDirectoryService)
                .setContactPoints(
                        eq(personId),
                        argThat(list -> list.size() == 1 && list.get(0).value().equals("test.user@example.com")));
    }

    @Test
    @Order(9)
    void searchPersons_includesCommercialIdentitySummary() {
        UUID janeId = UUID.randomUUID();
        when(personDirectoryService.resolveOrCreatePersonId(any(), any(), any(), any()))
                .thenReturn(janeId);

        CreatePersonResponse created = personService.createPerson(
                CreatePersonRequest.builder()
                        .firstName("Jane")
                        .lastName("Smith")
                        .preferredContactMethod(PreferredContactMethod.EMAIL)
                        .emails(List.of(CreatePersonRequest.EmailInput.builder()
                                .value("jane@example.com")
                                .isPrimary(true)
                                .build()))
                        .build(),
                USER);

        CommercialParty party = new CommercialParty();
        party.setCustomerNumber("CUST-2001");
        party.setLegalName("Acme Logistics");
        party.setDisplayName("Acme Logistics");
        commercialPartyRepository.save(party);

        PersonParty personParty =
                personRepository.findByPersonId(created.getPersonId()).orElseThrow();
        PartyRelationship relationship = new PartyRelationship();
        relationship.setFromParty(party);
        relationship.setToPerson(personParty);
        relationship.setEffectiveStartDate(LocalDate.of(2024, 1, 1));
        partyRelationshipRepository.save(relationship);

        when(personDirectoryService.searchPersons("jane@example.com"))
                .thenReturn(List.of(identity(janeId, "Jane", "Smith", "jane@example.com")));

        List<GetPersonResponse> results = personService.searchPersons(null, "jane@example.com", null, 20, 0);

        assertThat(results).hasSize(1);
        GetPersonResponse result = results.getFirst();
        assertThat(result.isIndividualCustomer()).isTrue();
        assertThat(result.isCommercialContact()).isTrue();
        assertThat(result.getCommercialAccountCount()).isEqualTo(1);
    }
}
