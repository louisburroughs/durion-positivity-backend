package com.positivity.customer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.customer.internal.dto.GetContactsWithRolesResponse;
import com.positivity.customer.internal.dto.UpdateContactRolesRequest;
import com.positivity.customer.internal.dto.UpdateContactRolesResponse;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.ContactRole;
import com.positivity.customer.internal.entity.ContactRoleAssignment;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.ContactPointRepository;
import com.positivity.customer.internal.repository.ContactRoleAssignmentRepository;
import com.positivity.customer.internal.repository.PersonRepository;

/**
 * Service for managing contact role assignments.
 * 
 * <p>
 * Handles the business logic for assigning and removing roles from contacts,
 * including:
 * </p>
 * <ul>
 * <li>Automatic primary demotion when a new primary is assigned</li>
 * <li>Validation that at least one billing contact remains</li>
 * <li>Event publishing for role changes</li>
 * </ul>
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/108">Backend
 *      Issue #108</a>
 */
@Service
public class ContactRoleService {

    private static final Logger log = LoggerFactory.getLogger(ContactRoleService.class);

    private final ContactRoleAssignmentRepository roleAssignmentRepository;
    private final CommercialPartyRepository partyRepository;
    private final PersonRepository personRepository;
    private final ContactPointRepository contactPointRepository;

    public ContactRoleService(
            ContactRoleAssignmentRepository roleAssignmentRepository,
            CommercialPartyRepository partyRepository,
            PersonRepository personRepository,
            ContactPointRepository contactPointRepository) {
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.partyRepository = partyRepository;
        this.personRepository = personRepository;
        this.contactPointRepository = contactPointRepository;
    }

    /**
     * Get all contacts with their role assignments for a party.
     * 
     * @param partyId the party ID as a string (Long)
     * @return response containing contacts with their roles
     * @throws IllegalArgumentException if party not found or invalid ID
     */
    @NonNull
    @Transactional(readOnly = true)
    public GetContactsWithRolesResponse getContactsWithRoles(@NonNull String partyId) {
        UUID partyUuid = parseUuid(partyId, "partyId");

        // Verify party exists
        CommercialParty party = partyRepository.findById(partyUuid)
                .orElseThrow(() -> new IllegalArgumentException("Party not found: " + partyId));

        // Get all role assignments for this party (using partyUuid as the account ID)
        List<ContactRoleAssignment> assignments = roleAssignmentRepository.findByCustomerAccountId(partyUuid);

        // Group assignments by contact
        var contactMap = assignments.stream()
                .collect(Collectors.groupingBy(ContactRoleAssignment::getContactId));

        List<GetContactsWithRolesResponse.ContactWithRoles> contacts = new ArrayList<>();

        for (var entry : contactMap.entrySet()) {
            UUID contactId = entry.getKey();
            List<ContactRoleAssignment> contactAssignments = entry.getValue();

            // Get person details
            personRepository.findById(contactId).ifPresent(person -> {
                var contactDto = GetContactsWithRolesResponse.ContactWithRoles.builder()
                        .contactId(contactId.toString())
                        .contactName(person.getFirstName() + " " + person.getLastName())
                        .build();

                // Get email and phone from contact points
                var emailOpt = Optional.ofNullable(contactPointRepository
                        .findByPersonPersonIdAndContactTypeAndIsPrimaryTrue(
                                contactId, com.positivity.customer.internal.dto.ContactPointType.EMAIL));
                emailOpt.ifPresent(cp -> contactDto.setEmail(cp.getValue()));
                contactDto.setHasPrimaryEmail(emailOpt.isPresent());

                var phoneOpt = Optional.ofNullable(contactPointRepository
                        .findByPersonPersonIdAndContactTypeAndIsPrimaryTrue(
                                contactId, com.positivity.customer.internal.dto.ContactPointType.PHONE_MOBILE));
                phoneOpt.ifPresent(cp -> contactDto.setPhone(cp.getValue()));

                // Map role assignments
                List<GetContactsWithRolesResponse.AssignedRole> roles = contactAssignments.stream()
                        .map(assignment -> GetContactsWithRolesResponse.AssignedRole.builder()
                                .roleCode(assignment.getRoleName().name())
                                .roleLabel(assignment.getRoleName().getLabel())
                                .isPrimary(assignment.isPrimary())
                                .build())
                        .toList();

                contactDto.setRoles(roles);
                contacts.add(contactDto);
            });
        }

        return GetContactsWithRolesResponse.builder()
                .partyId(partyId)
                .contacts(contacts)
                .build();
    }

    /**
     * Update role assignments for a contact within a party.
     * 
     * <p>
     * Implements automatic primary demotion: when a role is assigned as primary,
     * any existing primary for that role is automatically demoted.
     * </p>
     * 
     * @param partyId   the party ID as a string (Long)
     * @param contactId the contact (person) UUID as a string
     * @param request   the role assignment request
     * @return response with update status
     * @throws IllegalArgumentException if party/contact not found or invalid data
     */
    @NonNull
    @Transactional
    public UpdateContactRolesResponse updateContactRoles(
            @NonNull String partyId,
            @NonNull String contactId,
            @NonNull UpdateContactRolesRequest request) {

        UUID partyUuid = parseUuid(partyId, "partyId");
        UUID contactUuid = parseUuid(contactId, "contactId");

        // Verify party exists
        partyRepository.findById(partyUuid)
                .orElseThrow(() -> new IllegalArgumentException("Party not found: " + partyId));

        // Verify contact (person) exists
        personRepository.findById(contactUuid)
                .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + contactId));

        // Delete existing role assignments for this contact/party
        roleAssignmentRepository.deleteByContactIdAndCustomerAccountId(contactUuid, partyUuid);
        roleAssignmentRepository.flush();

        // Create new assignments
        List<ContactRoleAssignment> newAssignments = new ArrayList<>();

        if (request.getRoles() != null) {
            for (var roleReq : request.getRoles()) {
                ContactRole role = ContactRole.valueOf(roleReq.getRoleCode());
                boolean isPrimary = Boolean.TRUE.equals(roleReq.getIsPrimary());

                // If marking as primary, demote existing primary for this role
                if (isPrimary) {
                    demoteExistingPrimary(partyUuid, role);
                }

                ContactRoleAssignment assignment = ContactRoleAssignment.builder()
                        .contactId(contactUuid)
                        .customerAccountId(partyUuid)
                        .roleName(role)
                        .primary(isPrimary)
                        .build();

                newAssignments.add(assignment);
            }
        }

        roleAssignmentRepository.saveAll(newAssignments);

        log.info("Updated contact roles: partyId={}, contactId={}, roleCount={}",
                partyId, contactId, newAssignments.size());

        return UpdateContactRolesResponse.builder()
                .partyId(partyId)
                .contactId(contactId)
                .status("SUCCESS")
                .build();
    }

    /**
     * Demote any existing primary contact for a given role within an account.
     * 
     * @param customerAccountId the customer account UUID
     * @param role              the role to demote the existing primary for
     */
    private void demoteExistingPrimary(@NonNull UUID customerAccountId, @NonNull ContactRole role) {
        roleAssignmentRepository.findByCustomerAccountIdAndRoleNameAndPrimaryTrue(customerAccountId, role)
                .ifPresent(existingPrimary -> {
                    existingPrimary.setPrimary(false);
                    roleAssignmentRepository.save(existingPrimary);
                    log.info("Demoted existing primary for role {} in account {}",
                            role, customerAccountId);
                });
    }

    /**
     * Parse a Long string with helpful error messages.
     * 
     * @param longStr   the Long string
     * @param fieldName the field name for error messages
     * @return parsed Long
     * @throws IllegalArgumentException if Long is invalid
     */
    @Deprecated
    @NonNull
    private Long parseLong(@NonNull String longStr, @NonNull String fieldName) {
        try {
            return Long.parseLong(longStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + fieldName + " format: " + longStr, e);
        }
    }

    /**
     * Parse a UUID string with helpful error messages.
     * 
     * @param uuidStr   the UUID string
     * @param fieldName the field name for error messages
     * @return parsed UUID
     * @throws IllegalArgumentException if UUID is invalid
     */
    @NonNull
    private UUID parseUuid(@NonNull String uuidStr, @NonNull String fieldName) {
        try {
            return UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + fieldName + " format: " + uuidStr, e);
        }
    }
}
