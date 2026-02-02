package com.positivity.customer;

import com.positivity.customer.internal.dto.ContactPointType;
import com.positivity.customer.internal.dto.CreatePartyRelationshipRequest;
import com.positivity.customer.internal.dto.CreatePartyRelationshipResponse;
import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CreatePersonResponse;
import com.positivity.customer.internal.dto.GetCommercialAccountContactsResponse;
import com.positivity.customer.internal.dto.PartyRelationshipRole;
import com.positivity.customer.internal.dto.PreferredContactMethod;
import com.positivity.customer.internal.entity.*;
import com.positivity.customer.internal.repository.*;
import com.positivity.customer.service.PartyRelationshipService;
import com.positivity.customer.service.PersonService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Contract behavior integration tests for PartyRelationshipService.
 * <p>
 * Tests acceptance criteria from Issue #110: Associate Individuals to
 * Commercial Account.
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
class PartyRelationshipServiceContractBehaviorIT {

        @Autowired
        private PartyRelationshipService partyRelationshipService;

        @Autowired
        private PersonService personService;

        @Autowired
        private PartyRepository partyRepository;

        @Autowired
        private PersonRepository personRepository;

        @Autowired
        private PartyRelationshipRepository partyRelationshipRepository;

        @Autowired
        private ContactPointRepository contactPointRepository;

        private Party testParty;
        private Person testPerson1;
        private Person testPerson2;

        @BeforeEach
        void setUp() {
                // Clean up relationships first (due to FK constraints)
                partyRelationshipRepository.deleteAll();
                contactPointRepository.deleteAll();
                personRepository.deleteAll();
                partyRepository.deleteAll();

                // Create test party (commercial account)
                testParty = new Party();
                testParty.setPartyNumber("TEST-" + UUID.randomUUID().toString().substring(0, 8));
                testParty.setLegalName("Test Commercial Account");
                testParty.setStatus("ACTIVE");
                testParty = partyRepository.save(testParty);

                // Create test persons
                CreatePersonRequest personRequest1 = CreatePersonRequest.builder()
                                .firstName("Alice")
                                .lastName("Contact")
                                .preferredContactMethod(PreferredContactMethod.EMAIL)
                                .emails(List.of(
                                                CreatePersonRequest.EmailInput.builder()
                                                                .value("alice@example.com")
                                                                .isPrimary(true)
                                                                .build()))
                                .phones(List.of(
                                                CreatePersonRequest.PhoneInput.builder()
                                                                .value("+1-555-111-1111")
                                                                .type(ContactPointType.PHONE_MOBILE)
                                                                .isPrimary(true)
                                                                .build()))
                                .build();
                CreatePersonResponse response1 = personService.createPerson(personRequest1, UUID.randomUUID());
                testPerson1 = personRepository.findById(response1.getPersonId()).orElseThrow();

                CreatePersonRequest personRequest2 = CreatePersonRequest.builder()
                                .firstName("Bob")
                                .lastName("Contact")
                                .preferredContactMethod(PreferredContactMethod.PHONE_CALL)
                                .emails(List.of(
                                                CreatePersonRequest.EmailInput.builder()
                                                                .value("bob@example.com")
                                                                .isPrimary(true)
                                                                .build()))
                                .build();
                CreatePersonResponse response2 = personService.createPerson(personRequest2, UUID.randomUUID());
                testPerson2 = personRepository.findById(response2.getPersonId()).orElseThrow();
        }

        // ==========================================================================
        // Issue #110: Associate Individuals to Commercial Account - Tests
        // ==========================================================================

        /**
         * AC: Create relationship with single role succeeds.
         */
        @Test
        @Order(1)
        void createRelationship_singleRole_succeeds() {
                // Given: valid relationship request
                CreatePartyRelationshipRequest request = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson1.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.PRIMARY_CONTACT))
                                .effectiveStartDate(LocalDate.now())
                                .build();

                // When: creating relationship
                CreatePartyRelationshipResponse response = partyRelationshipService.createRelationship(
                                testParty.getId(), request, UUID.randomUUID());

                // Then: relationship created
                assertThat(response.getRelationshipId()).isNotNull();
                assertThat(response.getPartyId()).isEqualTo(String.valueOf(testParty.getId()));
                assertThat(response.getPersonId()).isEqualTo(testPerson1.getPersonId());
                assertThat(response.getRoles()).contains(PartyRelationshipRole.PRIMARY_CONTACT);
                assertThat(response.isPrimaryBillingContact()).isFalse();
        }

        /**
         * AC: Create relationship with multiple roles succeeds.
         */
        @Test
        @Order(2)
        void createRelationship_multipleRoles_succeeds() {
                // Given: request with multiple roles
                CreatePartyRelationshipRequest request = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson1.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.BILLING, PartyRelationshipRole.APPROVER))
                                .effectiveStartDate(LocalDate.now())
                                .build();

                // When: creating relationship
                CreatePartyRelationshipResponse response = partyRelationshipService.createRelationship(
                                testParty.getId(), request, UUID.randomUUID());

                // Then: relationship has both roles
                assertThat(response.getRoles()).hasSize(2);
                assertThat(response.getRoles()).containsExactlyInAnyOrder(
                                PartyRelationshipRole.BILLING, PartyRelationshipRole.APPROVER);
        }

        /**
         * AC: Creating primary billing contact succeeds.
         */
        @Test
        @Order(3)
        void createRelationship_primaryBillingContact_succeeds() {
                // Given: request with primary billing flag
                CreatePartyRelationshipRequest request = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson1.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.BILLING))
                                .effectiveStartDate(LocalDate.now())
                                .isPrimaryBillingContact(true)
                                .build();

                // When: creating relationship
                CreatePartyRelationshipResponse response = partyRelationshipService.createRelationship(
                                testParty.getId(), request, UUID.randomUUID());

                // Then: marked as primary billing
                assertThat(response.isPrimaryBillingContact()).isTrue();
                assertThat(response.isPreviousPrimaryDemoted()).isFalse();
        }

        /**
         * AC: Setting new primary billing contact demotes existing primary.
         * Business Rule: Exactly one primary billing contact per account at a time.
         */
        @Test
        @Order(4)
        void createRelationship_newPrimaryBilling_demotesExisting() {
                // Given: existing primary billing contact
                CreatePartyRelationshipRequest request1 = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson1.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.BILLING))
                                .effectiveStartDate(LocalDate.now())
                                .isPrimaryBillingContact(true)
                                .build();
                CreatePartyRelationshipResponse response1 = partyRelationshipService.createRelationship(
                                testParty.getId(), request1, UUID.randomUUID());
                assertThat(response1.isPrimaryBillingContact()).isTrue();

                // When: creating new primary billing contact
                CreatePartyRelationshipRequest request2 = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson2.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.BILLING))
                                .effectiveStartDate(LocalDate.now())
                                .isPrimaryBillingContact(true)
                                .build();
                CreatePartyRelationshipResponse response2 = partyRelationshipService.createRelationship(
                                testParty.getId(), request2, UUID.randomUUID());

                // Then: new relationship is primary, old one demoted
                assertThat(response2.isPrimaryBillingContact()).isTrue();
                assertThat(response2.isPreviousPrimaryDemoted()).isTrue();

                // Verify old relationship no longer primary
                PartyRelationship oldRelationship = partyRelationshipRepository
                                .findById(response1.getRelationshipId()).orElseThrow();
                assertThat(oldRelationship.isPrimaryBillingContact()).isFalse();
        }

        /**
         * AC: Primary billing contact must have BILLING role.
         */
        @Test
        @Order(5)
        void createRelationship_primaryBillingWithoutBillingRole_fails() {
                // Given: request with primary billing but no BILLING role
                CreatePartyRelationshipRequest request = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson1.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.PRIMARY_CONTACT)) // No BILLING role
                                .effectiveStartDate(LocalDate.now())
                                .isPrimaryBillingContact(true)
                                .build();

                // When/Then: expect validation error
                assertThatThrownBy(() -> partyRelationshipService.createRelationship(
                                testParty.getId(), request, UUID.randomUUID()))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(ex -> {
                                        ResponseStatusException rse = (ResponseStatusException) ex;
                                        assertThat(rse.getStatusCode().value()).isEqualTo(400);
                                        assertThat(rse.getReason()).contains("BILLING role");
                                });
        }

        /**
         * AC: Overlapping relationship for same party pair and role fails.
         */
        @Test
        @Order(6)
        void createRelationship_overlappingRelationship_fails() {
                // Given: existing active relationship
                CreatePartyRelationshipRequest request1 = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson1.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.PRIMARY_CONTACT))
                                .effectiveStartDate(LocalDate.now())
                                .build();
                partyRelationshipService.createRelationship(testParty.getId(), request1, UUID.randomUUID());

                // When/Then: creating overlapping relationship fails
                CreatePartyRelationshipRequest request2 = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson1.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.PRIMARY_CONTACT)) // Same role
                                .effectiveStartDate(LocalDate.now())
                                .build();

                assertThatThrownBy(() -> partyRelationshipService.createRelationship(
                                testParty.getId(), request2, UUID.randomUUID()))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(ex -> {
                                        ResponseStatusException rse = (ResponseStatusException) ex;
                                        assertThat(rse.getStatusCode().value()).isEqualTo(409); // Conflict
                                });
        }

        /**
         * AC: Relationship with non-existent party fails.
         */
        @Test
        @Order(7)
        void createRelationship_nonExistentParty_fails() {
                // Given: non-existent party ID
                Long nonExistentPartyId = 999999L;

                CreatePartyRelationshipRequest request = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson1.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.PRIMARY_CONTACT))
                                .effectiveStartDate(LocalDate.now())
                                .build();

                // When/Then: expect not found
                assertThatThrownBy(() -> partyRelationshipService.createRelationship(
                                nonExistentPartyId, request, UUID.randomUUID()))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(ex -> {
                                        ResponseStatusException rse = (ResponseStatusException) ex;
                                        assertThat(rse.getStatusCode().value()).isEqualTo(404);
                                });
        }

        /**
         * AC: Relationship with non-existent person fails.
         */
        @Test
        @Order(8)
        void createRelationship_nonExistentPerson_fails() {
                // Given: non-existent person ID
                UUID nonExistentPersonId = UUID.randomUUID();

                CreatePartyRelationshipRequest request = CreatePartyRelationshipRequest.builder()
                                .personId(nonExistentPersonId)
                                .roles(Set.of(PartyRelationshipRole.PRIMARY_CONTACT))
                                .effectiveStartDate(LocalDate.now())
                                .build();

                // When/Then: expect not found
                assertThatThrownBy(() -> partyRelationshipService.createRelationship(
                                testParty.getId(), request, UUID.randomUUID()))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(ex -> {
                                        ResponseStatusException rse = (ResponseStatusException) ex;
                                        assertThat(rse.getStatusCode().value()).isEqualTo(404);
                                });
        }

        // ==========================================================================
        // Get Contacts Tests
        // ==========================================================================

        @Test
        @Order(9)
        void getContacts_returnsAllActiveRelationships() {
                // Given: multiple relationships
                CreatePartyRelationshipRequest request1 = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson1.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.BILLING))
                                .effectiveStartDate(LocalDate.now())
                                .isPrimaryBillingContact(true)
                                .build();
                partyRelationshipService.createRelationship(testParty.getId(), request1, UUID.randomUUID());

                CreatePartyRelationshipRequest request2 = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson2.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.TECHNICAL))
                                .effectiveStartDate(LocalDate.now())
                                .build();
                partyRelationshipService.createRelationship(testParty.getId(), request2, UUID.randomUUID());

                // When: getting contacts
                GetCommercialAccountContactsResponse response = partyRelationshipService
                                .getContactsForCommercialAccount(testParty.getId(), null, "ACTIVE");

                // Then: both contacts returned
                assertThat(response.getContacts()).hasSize(2);
                assertThat(response.getContacts())
                                .anyMatch(c -> c.getIndividualId().equals(testPerson1.getPersonId())
                                                && c.isPrimaryBilling());
                assertThat(response.getContacts())
                                .anyMatch(c -> c.getIndividualId().equals(testPerson2.getPersonId())
                                                && !c.isPrimaryBilling());
        }

        @Test
        @Order(10)
        void getContacts_filterByRole_returnsMatchingContacts() {
                // Given: relationships with different roles
                CreatePartyRelationshipRequest request1 = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson1.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.BILLING))
                                .effectiveStartDate(LocalDate.now())
                                .build();
                partyRelationshipService.createRelationship(testParty.getId(), request1, UUID.randomUUID());

                CreatePartyRelationshipRequest request2 = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson2.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.TECHNICAL))
                                .effectiveStartDate(LocalDate.now())
                                .build();
                partyRelationshipService.createRelationship(testParty.getId(), request2, UUID.randomUUID());

                // When: filtering by BILLING role
                GetCommercialAccountContactsResponse response = partyRelationshipService
                                .getContactsForCommercialAccount(testParty.getId(),
                                                List.of(PartyRelationshipRole.BILLING), "ACTIVE");

                // Then: only billing contact returned
                assertThat(response.getContacts()).hasSize(1);
                assertThat(response.getContacts().get(0).getIndividualId()).isEqualTo(testPerson1.getPersonId());
        }

        // ==========================================================================
        // Deactivate Relationship Tests
        // ==========================================================================

        @Test
        @Order(11)
        void deactivateRelationship_setsEndDate() {
                // Given: active relationship
                CreatePartyRelationshipRequest request = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson1.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.PRIMARY_CONTACT))
                                .effectiveStartDate(LocalDate.now().minusDays(30))
                                .build();
                CreatePartyRelationshipResponse created = partyRelationshipService.createRelationship(
                                testParty.getId(), request, UUID.randomUUID());

                // When: deactivating
                partyRelationshipService.deactivateRelationship(created.getRelationshipId(), UUID.randomUUID());

                // Then: end date set to today
                PartyRelationship relationship = partyRelationshipRepository
                                .findById(created.getRelationshipId()).orElseThrow();
                assertThat(relationship.getEffectiveEndDate()).isEqualTo(LocalDate.now());
                assertThat(relationship.isActive()).isFalse();
        }

        // ==========================================================================
        // Designate Primary Billing Tests
        // ==========================================================================

        @Test
        @Order(12)
        void designatePrimaryBilling_existingRelationship_succeeds() {
                // Given: relationship with BILLING role but not primary
                CreatePartyRelationshipRequest request = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson1.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.BILLING))
                                .effectiveStartDate(LocalDate.now())
                                .isPrimaryBillingContact(false)
                                .build();
                CreatePartyRelationshipResponse created = partyRelationshipService.createRelationship(
                                testParty.getId(), request, UUID.randomUUID());

                // When: designating as primary
                partyRelationshipService.designatePrimaryBillingContact(
                                testParty.getId(), created.getRelationshipId(), UUID.randomUUID());

                // Then: relationship is now primary
                PartyRelationship relationship = partyRelationshipRepository
                                .findById(created.getRelationshipId()).orElseThrow();
                assertThat(relationship.isPrimaryBillingContact()).isTrue();
        }

        @Test
        @Order(13)
        void designatePrimaryBilling_withoutBillingRole_fails() {
                // Given: relationship without BILLING role
                CreatePartyRelationshipRequest request = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson1.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.TECHNICAL)) // Not BILLING
                                .effectiveStartDate(LocalDate.now())
                                .build();
                CreatePartyRelationshipResponse created = partyRelationshipService.createRelationship(
                                testParty.getId(), request, UUID.randomUUID());

                // When/Then: expect validation error
                assertThatThrownBy(() -> partyRelationshipService.designatePrimaryBillingContact(
                                testParty.getId(), created.getRelationshipId(), UUID.randomUUID()))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(ex -> {
                                        ResponseStatusException rse = (ResponseStatusException) ex;
                                        assertThat(rse.getStatusCode().value()).isEqualTo(400);
                                        assertThat(rse.getReason()).contains("BILLING role");
                                });
        }
}
