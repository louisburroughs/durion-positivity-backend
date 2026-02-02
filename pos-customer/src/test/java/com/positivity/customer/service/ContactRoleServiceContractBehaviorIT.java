package com.positivity.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.customer.internal.dto.GetContactsWithRolesResponse;
import com.positivity.customer.internal.dto.UpdateContactRolesRequest;
import com.positivity.customer.internal.dto.UpdateContactRolesRequest.RoleAssignment;
import com.positivity.customer.internal.entity.ContactRole;
import com.positivity.customer.internal.entity.ContactRoleAssignment;
import com.positivity.customer.internal.entity.Party;
import com.positivity.customer.internal.entity.Person;
import com.positivity.customer.internal.repository.ContactRoleAssignmentRepository;
import com.positivity.customer.internal.repository.PartyRepository;
import com.positivity.customer.internal.repository.PersonRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

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
class ContactRoleServiceContractBehaviorIT {

    @Autowired
    private ContactRoleService contactRoleService;

    @Autowired
    private PartyRepository partyRepository;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private ContactRoleAssignmentRepository roleAssignmentRepository;

    private Party testParty;
    private Person testContact;
    private UUID testContactUuid;
    private UUID testPartyUuid;

    @BeforeEach
    void setUp() {
        // Create test party
        testParty = new Party();
        testParty.setPartyType("ORGANIZATION");
        testParty.setLegalName("Test Party");
        testParty.setPartyNumber("PARTY-TEST-" + System.currentTimeMillis());
        testParty.setStatus("ACTIVE");
        testParty = partyRepository.save(testParty);
        testPartyUuid = UUID.nameUUIDFromBytes(("party-" + testParty.getId()).getBytes());

        // Create test contact person
        Person contactPerson = new Person();
        contactPerson.setPartyId(testParty.getId());
        contactPerson.setFirstName("John");
        contactPerson.setLastName("Doe");
        contactPerson = personRepository.save(contactPerson);
        testContact = contactPerson;
        testContactUuid = contactPerson.getPersonId();
    }

    @AfterEach
    void tearDown() {
        roleAssignmentRepository.deleteAll();
        personRepository.deleteAll();
        partyRepository.deleteAll();
    }

    // ========== getContactsWithRoles ==========

    @Test
    @DisplayName("getContactsWithRoles - Success: Returns empty list when no contacts assigned")
    void getContactsWithRoles_emptyList() {
        GetContactsWithRolesResponse response = contactRoleService
            .getContactsWithRoles(String.valueOf(testParty.getId()));
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
            .getContactsWithRoles(String.valueOf(testParty.getId()));

        assertThat(response.getContacts()).hasSize(1);
        var contact = response.getContacts().get(0);
        assertThat(contact.getContactId()).isEqualTo(testContactUuid.toString());
        assertThat(contact.getFirstName()).isEqualTo("John");
        assertThat(contact.getLastName()).isEqualTo("Doe");
        assertThat(contact.getAssignedRoles()).hasSize(1);
        assertThat(contact.getAssignedRoles().get(0).getRoleCode()).isEqualTo("BILLING");
        assertThat(contact.getAssignedRoles().get(0).getIsPrimary()).isTrue();
    }

    @Test
    @DisplayName("getContactsWithRoles - Not Found: Party does not exist")
    void getContactsWithRoles_partyNotFound() {
        assertThatThrownBy(() -> contactRoleService.getContactsWithRoles("999999"))
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
                String.valueOf(testParty.getPartyId()),
                testContactUuid.toString(),
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
        Person contact2 = new Person();
        contact2.setPartyId(testParty.getId());
        contact2.setFirstName("Jane");
        contact2.setLastName("Smith");
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
                String.valueOf(testParty.getPartyId()),
                testContactUuid.toString(),
                request);

        // Verify old primary was demoted
        List<ContactRoleAssignment> primaryAssignments = roleAssignmentRepository
            .findByCustomerAccountIdAndRoleNameAndPrimaryTrue(testPartyUuid, ContactRole.BILLING)
        assertThat(primaryAssignments.get(0).getContactId()).isEqualTo(testContactUuid);
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
                String.valueOf(testParty.getPartyId()),
                testContactUuid.toString(),
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
                "999999",
                testContactUuid.toString(),
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
                String.valueOf(testParty.getPartyId()),
                nonExistentContactUuid.toString(),
                request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Person not found");
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
                String.valueOf(testParty.getPartyId()),
                testContactUuid.toString(),
                request))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
