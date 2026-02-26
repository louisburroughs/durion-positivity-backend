package com.positivity.customer.service;

import com.positivity.customer.BaseContractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.positivity.customer.internal.dto.GetContactsWithRolesResponse;
import com.positivity.customer.internal.dto.UpdateContactRolesRequest;
import com.positivity.customer.internal.dto.UpdateContactRolesRequest.RoleAssignment;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.Contact;
import com.positivity.customer.internal.entity.ContactRole;
import com.positivity.customer.internal.entity.ContactRoleAssignment;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.ContactRoleAssignmentRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;

/**
 * Contract behavior integration tests for Contact Role Management (CAP-090).
 * Tests verify:
 * - getContactsWithRoles - List contacts with roles
 * - updateContactRoles - Update contact roles
 *
 * @author Durion Platform
 * @since CAP-090
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ContactRoleServiceContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private ContactRoleService contactRoleService;

        @Autowired
        private CommercialPartyRepository partyRepository;

        @Autowired
        private PersonPartyRepository personRepository;

        @Autowired
        private ContactRoleAssignmentRepository roleAssignmentRepository;

        private CommercialParty testParty;
        private UUID testContactUuid;
        private UUID testPartyUuid;

        @BeforeEach
        void setUp() {
                // Create test party with a contact
                testParty = new CommercialParty();
                testParty.setPartyType(PartyType.COMMERCIAL);
                testParty.setLegalName("Test Party");
                testParty.setDisplayName("Test Party");
                testParty.setPartyNumber("PARTY-TEST-" + System.currentTimeMillis());
                testParty.setCustomerNumber("CUST-TEST-" + System.currentTimeMillis());
                testParty.setStatus(AccountStatus.ACTIVE);

                // Create and add a contact to the party before saving
                Contact contact = new Contact();
                contact.setCommercialParty(testParty);
                contact.setFirstName("John");
                contact.setLastName("Doe");
                contact.setActive(true);
                testParty.getContacts().add(contact);

                testParty = partyRepository.save(testParty);
                testPartyUuid = testParty.getPartyId();

                // Create test person (independent entity for contact role assignments)
                PersonParty contactPerson = new PersonParty();
                contactPerson.setFirstName("John");
                contactPerson.setLastName("Doe");
                contactPerson.setCustomerNumber("CUST-PERSON-" + System.currentTimeMillis());
                contactPerson.setPreferredContactMethod(
                                com.positivity.customer.internal.enums.PreferredContactMethod.EMAIL);
                contactPerson = personRepository.save(contactPerson);
                testContactUuid = contactPerson.getPersonId();
        }

        @Test
        @DisplayName("getContactsWithRoles - Success: Returns contacts with roles")
        void getContactsWithRoles_withRoles() {
                // Create role assignment
                ContactRoleAssignment assignment = ContactRoleAssignment.builder()
                                .contactId(testContactUuid)
                                .customerAccountId(testPartyUuid)
                                .roleName(ContactRole.BILLING)
                                .primary(true)
                                .build();
                roleAssignmentRepository.save(assignment);

                GetContactsWithRolesResponse response = contactRoleService
                                .getContactsWithRoles(testParty.getPartyId());

                assertThat(response.getContacts()).hasSize(1);
                var contact = response.getContacts().get(0);
                assertThat(contact.getContactId()).isEqualTo(testContactUuid.toString());
                assertThat(contact.getContactName()).isEqualTo("John Doe");
                assertThat(contact.getRoles()).hasSize(1);
                assertThat(contact.getRoles().get(0).getRoleCode()).isEqualTo("BILLING");
                assertThat(contact.getRoles().get(0).getIsPrimary()).isTrue();
        }

        @Test
        @DisplayName("getContactsWithRoles - Not Found: Party does not exist")
        void getContactsWithRoles_partyNotFound() {
                assertThatThrownBy(() -> contactRoleService.getContactsWithRoles(UUID.randomUUID()))
                                .isInstanceOf(ResponseStatusException.class)
                                .hasMessageContaining("Party not found");
        }

        // ========== updateContactRoles ==========

        @Test
        @DisplayName("updateContactRoles - Success: Assigns new roles to contact")
        void updateContactRoles_assignNewRoles() {
                UpdateContactRolesRequest request = UpdateContactRolesRequest.builder()
                                .roles(List.of(
                                                RoleAssignment.builder()
                                                                .roleCode("BILLING")
                                                                .isPrimary(true)
                                                                .build(),
                                                RoleAssignment.builder()
                                                                .roleCode("OPERATIONS")
                                                                .isPrimary(false)
                                                                .build()))
                                .build();

                contactRoleService.updateContactRoles(
                                testParty.getPartyId(),
                                testContactUuid,
                                request);

                // Verify database state
                List<ContactRoleAssignment> assignments = roleAssignmentRepository
                                .findByCustomerAccountId(testPartyUuid);
                assertThat(assignments).hasSize(2);
                assertThat(assignments)
                                .extracting(ContactRoleAssignment::getRoleName)
                                .containsExactlyInAnyOrder(ContactRole.BILLING, ContactRole.OPERATIONS);
                assertThat(assignments)
                                .filteredOn(ContactRoleAssignment::isPrimary)
                                .extracting(ContactRoleAssignment::getRoleName)
                                .containsExactly(ContactRole.BILLING);
        }

        @Test
        @DisplayName("updateContactRoles - Success: Demotes existing primary when new primary assigned")
        void updateContactRoles_demotesExistingPrimary() {
                // Create another contact
                PersonParty contact2 = new PersonParty();
                contact2.setFirstName("Jane");
                contact2.setLastName("Smith");
                contact2.setCustomerNumber("CUST-PERSON2-" + System.currentTimeMillis());
                contact2.setPreferredContactMethod(com.positivity.customer.internal.enums.PreferredContactMethod.EMAIL);
                contact2 = personRepository.save(contact2);
                UUID contact2Uuid = contact2.getPersonId();

                // Assign contact2 as primary billing contact
                ContactRoleAssignment existingPrimary = ContactRoleAssignment.builder()
                                .contactId(contact2Uuid)
                                .customerAccountId(testPartyUuid)
                                .roleName(ContactRole.BILLING)
                                .primary(true)
                                .build();
                roleAssignmentRepository.save(existingPrimary);

                // Now assign contact1 as primary billing contact
                UpdateContactRolesRequest request = UpdateContactRolesRequest.builder()
                                .roles(List.of(
                                                RoleAssignment.builder()
                                                                .roleCode("BILLING")
                                                                .isPrimary(true)
                                                                .build()))
                                .build();

                contactRoleService.updateContactRoles(
                                testParty.getPartyId(),
                                testContactUuid,
                                request);

                // Verify old primary was demoted
                Optional<ContactRoleAssignment> primaryAssignment = roleAssignmentRepository
                                .findByCustomerAccountIdAndRoleNameAndPrimaryTrue(testPartyUuid, ContactRole.BILLING);
                assertThat(primaryAssignment).isPresent();
                assertThat(primaryAssignment.get().getContactId()).isEqualTo(testContactUuid);
        }

        @Test
        @DisplayName("updateContactRoles - Success: Removes all roles when empty list provided")
        void updateContactRoles_removeAllRoles() {
                // Create initial role
                ContactRoleAssignment assignment = ContactRoleAssignment.builder()
                                .contactId(testContactUuid)
                                .customerAccountId(testPartyUuid)
                                .roleName(ContactRole.BILLING)
                                .primary(true)
                                .build();
                roleAssignmentRepository.save(assignment);

                UpdateContactRolesRequest request = UpdateContactRolesRequest.builder()
                                .roles(List.of())
                                .build();

                contactRoleService.updateContactRoles(
                                testParty.getPartyId(),
                                testContactUuid,
                                request);

                // Verify all roles removed
                List<ContactRoleAssignment> assignments = roleAssignmentRepository
                                .findByCustomerAccountId(testPartyUuid);
                assertThat(assignments).isEmpty();
        }

        @Test
        @DisplayName("updateContactRoles - Not Found: Party does not exist")
        void updateContactRoles_partyNotFound() {
                UpdateContactRolesRequest request = UpdateContactRolesRequest.builder()
                                .roles(List.of())
                                .build();

                assertThatThrownBy(() -> contactRoleService.updateContactRoles(
                                UUID.randomUUID(),
                                testContactUuid,
                                request))
                                .isInstanceOf(ResponseStatusException.class)
                                .hasMessageContaining("Party not found");
        }

        @Test
        @DisplayName("updateContactRoles - Not Found: Contact does not exist")
        void updateContactRoles_contactNotFound() {
                UpdateContactRolesRequest request = UpdateContactRolesRequest.builder()
                                .roles(List.of())
                                .build();

                UUID nonExistentContactUuid = UUID.randomUUID();

                assertThatThrownBy(() -> contactRoleService.updateContactRoles(
                                testParty.getPartyId(),
                                nonExistentContactUuid,
                                request))
                                .isInstanceOf(ResponseStatusException.class)
                                .hasMessageContaining("PersonParty not found");
        }

        @Test
        @DisplayName("updateContactRoles - Bad Request: Invalid roleCode")
        void updateContactRoles_invalidRoleCode() {
                UpdateContactRolesRequest request = UpdateContactRolesRequest.builder()
                                .roles(List.of(
                                                RoleAssignment.builder()
                                                                .roleCode("INVALID_ROLE")
                                                                .isPrimary(false)
                                                                .build()))
                                .build();

                assertThatThrownBy(() -> contactRoleService.updateContactRoles(
                                testParty.getPartyId(),
                                testContactUuid,
                                request))
                                .isInstanceOf(IllegalArgumentException.class);
        }
}
