package com.positivity.customer;

import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CreatePersonResponse;
import com.positivity.customer.internal.entity.*;
import com.positivity.customer.internal.repository.*;
import com.positivity.customer.service.PersonService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Contract behavior integration tests for Party Management services.
 * <p>
 * Tests acceptance criteria from:
 * - Issue #111: Create Individual Person Record
 * - Issue #110: Associate Individuals to Commercial Account
 * </p>
 * <p>
 * These tests verify the business logic behavior at the service layer,
 * using real database interactions (H2 in-memory).
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PersonServiceContractBehaviorIT {

    @Autowired
    private PersonService personService;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private ContactPointRepository contactPointRepository;

    @BeforeEach
    void setUp() {
        // Clean up before each test
        contactPointRepository.deleteAll();
        personRepository.deleteAll();
    }

    // ==========================================================================
    // Issue #111: Create Individual Person Record - Acceptance Criteria Tests
    // ==========================================================================

    /**
     * AC1: Minimal create (name + preferred method) returns 201 and persists a
     * Person.
     */
    @Test
    @Order(1)
    void ac1_minimalCreate_persistsPerson() {
        // Given: minimal valid request
        CreatePersonRequest request = CreatePersonRequest.builder()
                .firstName("John")
                .lastName("Doe")
                .preferredContactMethod(PreferredContactMethod.EMAIL)
                .build();

        // When: creating person
        CreatePersonResponse response = personService.createPerson(request, UUID.randomUUID());

        // Then: person is persisted
        assertThat(response.getPersonId()).isNotNull();
        assertThat(response.getFirstName()).isEqualTo("John");
        assertThat(response.getLastName()).isEqualTo("Doe");
        assertThat(response.getPreferredContactMethod()).isEqualTo(PreferredContactMethod.EMAIL);

        // Verify in database
        Person savedPerson = personRepository.findById(response.getPersonId()).orElse(null);
        assertThat(savedPerson).isNotNull();
        assertThat(savedPerson.getFirstName()).isEqualTo("John");
        assertThat(savedPerson.getLastName()).isEqualTo("Doe");
    }

    /**
     * AC2: Create with two emails and two phone numbers persists four
     * ContactPoints.
     */
    @Test
    @Order(2)
    void ac2_createWithContactPoints_persistsAllContactPoints() {
        // Given: request with multiple contact points
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

        // When: creating person
        CreatePersonResponse response = personService.createPerson(request, UUID.randomUUID());

        // Then: 4 contact points persisted
        assertThat(response.getContactPointsCreated()).isEqualTo(4);

        // Verify in database
        List<ContactPoint> contactPoints = contactPointRepository.findByPersonPersonId(response.getPersonId());
        assertThat(contactPoints).hasSize(4);

        // Verify email contact points
        List<ContactPoint> emails = contactPoints.stream()
                .filter(cp -> cp.getContactType() == ContactPointType.EMAIL)
                .toList();
        assertThat(emails).hasSize(2);
        assertThat(emails).anyMatch(e -> e.getValue().equals("jane.primary@example.com") && e.isPrimary());
        assertThat(emails).anyMatch(e -> e.getValue().equals("jane.secondary@example.com") && !e.isPrimary());

        // Verify phone contact points
        List<ContactPoint> phones = contactPoints.stream()
                .filter(cp -> cp.getContactType() == ContactPointType.PHONE_MOBILE
                        || cp.getContactType() == ContactPointType.PHONE_WORK)
                .toList();
        assertThat(phones).hasSize(2);
    }

    /**
     * AC3: Missing lastName returns 400 and persists nothing.
     */
    @Test
    @Order(3)
    void ac3_missingLastName_returns400AndPersistsNothing() {
        // Given: request without lastName
        CreatePersonRequest request = CreatePersonRequest.builder()
                .firstName("John")
                .lastName(null) // Missing
                .preferredContactMethod(PreferredContactMethod.EMAIL)
                .build();

        // When/Then: expect validation error
        assertThatThrownBy(() -> personService.createPerson(request, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(400);
                });

        // Verify nothing persisted
        assertThat(personRepository.count()).isZero();
    }

    /**
     * AC4: Invalid email format returns 400 and persists nothing.
     */
    @Test
    @Order(4)
    void ac4_invalidEmailFormat_returns400AndPersistsNothing() {
        // Given: request with invalid email
        CreatePersonRequest request = CreatePersonRequest.builder()
                .firstName("John")
                .lastName("Doe")
                .preferredContactMethod(PreferredContactMethod.EMAIL)
                .emails(List.of(
                        CreatePersonRequest.EmailInput.builder()
                                .value("not-an-email") // Invalid
                                .isPrimary(true)
                                .build()))
                .build();

        // When/Then: expect validation error
        assertThatThrownBy(() -> personService.createPerson(request, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(400);
                    assertThat(rse.getReason()).contains("Invalid email");
                });

        // Verify nothing persisted
        assertThat(personRepository.count()).isZero();
    }

    // ==========================================================================
    // Additional Tests for Issue #111
    // ==========================================================================

    @Test
    @Order(5)
    void getPerson_existingPerson_returnsPersonWithContactPoints() {
        // Given: an existing person with contact points
        CreatePersonRequest request = CreatePersonRequest.builder()
                .firstName("Test")
                .lastName("User")
                .preferredContactMethod(PreferredContactMethod.SMS)
                .emails(List.of(
                        CreatePersonRequest.EmailInput.builder()
                                .value("test@example.com")
                                .isPrimary(true)
                                .build()))
                .build();
        CreatePersonResponse created = personService.createPerson(request, UUID.randomUUID());

        // When: getting person by ID
        GetPersonResponse response = personService.getPerson(created.getPersonId());

        // Then: person with contact points returned
        assertThat(response.getPersonId()).isEqualTo(created.getPersonId());
        assertThat(response.getFirstName()).isEqualTo("Test");
        assertThat(response.getLastName()).isEqualTo("User");
        assertThat(response.getContactPoints()).hasSize(1);
        assertThat(response.getContactPoints().get(0).getValue()).isEqualTo("test@example.com");
    }

    @Test
    @Order(6)
    void getPerson_nonExistentPerson_returns404() {
        // Given: non-existent person ID
        UUID nonExistentId = UUID.randomUUID();

        // When/Then: expect not found
        assertThatThrownBy(() -> personService.getPerson(nonExistentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(404);
                });
    }

    @Test
    @Order(7)
    void searchPersons_byName_returnsMatchingPersons() {
        // Given: multiple persons
        CreatePersonRequest request1 = CreatePersonRequest.builder()
                .firstName("Alice")
                .lastName("Johnson")
                .preferredContactMethod(PreferredContactMethod.EMAIL)
                .build();
        CreatePersonRequest request2 = CreatePersonRequest.builder()
                .firstName("Bob")
                .lastName("Johnson")
                .preferredContactMethod(PreferredContactMethod.PHONE_CALL)
                .build();
        CreatePersonRequest request3 = CreatePersonRequest.builder()
                .firstName("Charlie")
                .lastName("Brown")
                .preferredContactMethod(PreferredContactMethod.SMS)
                .build();
        personService.createPerson(request1, UUID.randomUUID());
        personService.createPerson(request2, UUID.randomUUID());
        personService.createPerson(request3, UUID.randomUUID());

        // When: searching by last name
        List<GetPersonResponse> results = personService.searchPersons("Johnson", null, null, 20, 0);

        // Then: only Johnsons returned
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(p -> p.getLastName().equals("Johnson"));
    }

    @Test
    @Order(8)
    void emailNormalization_storesLowercase() {
        // Given: request with mixed-case email
        CreatePersonRequest request = CreatePersonRequest.builder()
                .firstName("Test")
                .lastName("User")
                .preferredContactMethod(PreferredContactMethod.EMAIL)
                .emails(List.of(
                        CreatePersonRequest.EmailInput.builder()
                                .value("Test.User@EXAMPLE.COM")
                                .isPrimary(true)
                                .build()))
                .build();

        // When: creating person
        CreatePersonResponse response = personService.createPerson(request, UUID.randomUUID());

        // Then: email stored in lowercase
        List<ContactPoint> contacts = contactPointRepository.findByPersonPersonId(response.getPersonId());
        assertThat(contacts).hasSize(1);
        assertThat(contacts.get(0).getValue()).isEqualTo("test.user@example.com");
    }
}
