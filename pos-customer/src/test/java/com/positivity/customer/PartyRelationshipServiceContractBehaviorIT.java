package com.positivity.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
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
import org.springframework.web.server.ResponseStatusException;

import com.positivity.customer.internal.dto.CreatePartyRelationshipRequest;
import com.positivity.customer.internal.dto.CreatePartyRelationshipResponse;
import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CreatePersonResponse;
import com.positivity.customer.internal.dto.GetCommercialAccountContactsResponse;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.Contact;
import com.positivity.customer.internal.entity.PartyRelationship;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.ContactPointType;
import com.positivity.customer.internal.enums.PartyRelationshipRole;
import com.positivity.customer.internal.enums.PreferredContactMethod;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.ContactPointRepository;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.service.PartyRelationshipService;
import com.positivity.customer.service.PersonService;

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
class PartyRelationshipServiceContractBehaviorIT extends BaseContractIntegrationTest {

        private static final Clock FIXED_CLOCK = Clock.fixed(java.time.Instant.parse("2024-01-01T00:00:00Z"),
                        java.time.ZoneOffset.UTC);

        @Autowired
        private PartyRelationshipService partyRelationshipService;

        @Autowired
        private PersonService personService;

        @Autowired
        private CommercialPartyRepository partyRepository;

        @Autowired
        private PersonPartyRepository personRepository;

        @Autowired
        private PartyRelationshipRepository partyRelationshipRepository;

        @Autowired
        private ContactPointRepository contactPointRepository;

        private CommercialParty testParty;
        private PersonParty testPerson1;
        private PersonParty testPerson2;

        @BeforeEach
        void setUp() {
                // Clean up relationships first (due to FK constraints)
                partyRelationshipRepository.deleteAll();
                contactPointRepository.deleteAll();
                personRepository.deleteAll();
                partyRepository.deleteAll();

                // Create test party (commercial account)
                testParty = new CommercialParty();
                testParty.setPartyNumber("TEST-" + UUID.randomUUID().toString().substring(0, 8));
                testParty.setLegalName("Test Commercial Account");
                testParty.setDisplayName("Test Commercial Account");
                testParty.setCustomerNumber("CUST-TEST-" + UUID.randomUUID().toString().substring(0, 8));
                testParty.setStatus(AccountStatus.ACTIVE);

                Contact contact = new Contact();
                contact.setCommercialParty(testParty);
                contact.setFirstName("Primary");
                contact.setLastName("Contact");
                contact.setActive(true);
                testParty.getContacts().add(contact);

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
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
                                .build();

                // When: creating relationship
                CreatePartyRelationshipResponse response = partyRelationshipService.createRelationship(
                                testParty.getPartyId(), request, UUID.randomUUID());

                // Then: relationship created
                assertThat(response.getRelationshipId()).isNotNull();
                assertThat(response.getPartyId()).isEqualTo(String.valueOf(testParty.getPartyId()));
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
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
                                .build();

                // When: creating relationship
                CreatePartyRelationshipResponse response = partyRelationshipService.createRelationship(
                                testParty.getPartyId(), request, UUID.randomUUID());

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
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
                                .isPrimaryBillingContact(true)
                                .build();

                // When: creating relationship
                CreatePartyRelationshipResponse response = partyRelationshipService.createRelationship(
                                testParty.getPartyId(), request, UUID.randomUUID());

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
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
                                .isPrimaryBillingContact(true)
                                .build();
                CreatePartyRelationshipResponse response1 = partyRelationshipService.createRelationship(
                                testParty.getPartyId(), request1, UUID.randomUUID());
                assertThat(response1.isPrimaryBillingContact()).isTrue();

                // When: creating new primary billing contact
                CreatePartyRelationshipRequest request2 = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson2.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.BILLING))
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
                                .isPrimaryBillingContact(true)
                                .build();
                CreatePartyRelationshipResponse response2 = partyRelationshipService.createRelationship(
                                testParty.getPartyId(), request2, UUID.randomUUID());

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
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
                                .isPrimaryBillingContact(true)
                                .build();

                // When/Then: expect validation error
                assertThatThrownBy(() -> partyRelationshipService.createRelationship(
                                testParty.getPartyId(), request, UUID.randomUUID()))
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
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
                                .build();
                partyRelationshipService.createRelationship(testParty.getPartyId(), request1, UUID.randomUUID());

                // When/Then: creating overlapping relationship fails
                CreatePartyRelationshipRequest request2 = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson1.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.PRIMARY_CONTACT)) // Same role
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
                                .build();

                assertThatThrownBy(() -> partyRelationshipService.createRelationship(
                                testParty.getPartyId(), request2, UUID.randomUUID()))
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
                UUID nonExistentPartyId = UUID.randomUUID();

                CreatePartyRelationshipRequest request = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson1.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.PRIMARY_CONTACT))
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
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
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
                                .build();

                // When/Then: expect not found
                assertThatThrownBy(() -> partyRelationshipService.createRelationship(
                                testParty.getPartyId(), request, UUID.randomUUID()))
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
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
                                .isPrimaryBillingContact(true)
                                .build();
                partyRelationshipService.createRelationship(testParty.getPartyId(), request1, UUID.randomUUID());

                CreatePartyRelationshipRequest request2 = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson2.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.TECHNICAL))
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
                                .build();
                partyRelationshipService.createRelationship(testParty.getPartyId(), request2, UUID.randomUUID());

                // When: getting contacts
                GetCommercialAccountContactsResponse response = partyRelationshipService
                                .getContactsForCommercialAccount(testParty.getPartyId(), null, "ACTIVE");

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
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
                                .build();
                partyRelationshipService.createRelationship(testParty.getPartyId(), request1, UUID.randomUUID());

                CreatePartyRelationshipRequest request2 = CreatePartyRelationshipRequest.builder()
                                .personId(testPerson2.getPersonId())
                                .roles(Set.of(PartyRelationshipRole.TECHNICAL))
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
                                .build();
                partyRelationshipService.createRelationship(testParty.getPartyId(), request2, UUID.randomUUID());

                // When: filtering by BILLING role
                GetCommercialAccountContactsResponse response = partyRelationshipService
                                .getContactsForCommercialAccount(testParty.getPartyId(),
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
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK).minusDays(30))
                                .build();
                CreatePartyRelationshipResponse created = partyRelationshipService.createRelationship(
                                testParty.getPartyId(), request, UUID.randomUUID());

                // When: deactivating
                partyRelationshipService.deactivateRelationship(created.getRelationshipId(), UUID.randomUUID());

                // Then: end date set to today
                PartyRelationship relationship = partyRelationshipRepository
                                .findById(created.getRelationshipId()).orElseThrow();
                assertThat(relationship.getEffectiveEndDate()).isEqualTo(LocalDate.now(FIXED_CLOCK));
                assertThat(relationship.isActive(LocalDate.now(FIXED_CLOCK))).isFalse();
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
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
                                .isPrimaryBillingContact(false)
                                .build();
                CreatePartyRelationshipResponse created = partyRelationshipService.createRelationship(
                                testParty.getPartyId(), request, UUID.randomUUID());

                // When: designating as primary
                partyRelationshipService.designatePrimaryBillingContact(
                                testParty.getPartyId(), created.getRelationshipId(), UUID.randomUUID());

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
                                .effectiveStartDate(LocalDate.now(FIXED_CLOCK))
                                .build();
                CreatePartyRelationshipResponse created = partyRelationshipService.createRelationship(
                                testParty.getPartyId(), request, UUID.randomUUID());

                // When/Then: expect validation error
                assertThatThrownBy(() -> partyRelationshipService.designatePrimaryBillingContact(
                                testParty.getPartyId(), created.getRelationshipId(), UUID.randomUUID()))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(ex -> {
                                        ResponseStatusException rse = (ResponseStatusException) ex;
                                        assertThat(rse.getStatusCode().value()).isEqualTo(400);
                                        assertThat(rse.getReason()).contains("BILLING role");
                                });
        }
}
