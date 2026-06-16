package com.positivity.customer.internal.service;

import com.positivity.customer.internal.client.PeopleClient;
import com.positivity.customer.internal.dto.GetContactsWithRolesResponse;
import com.positivity.customer.internal.dto.UpdateContactRolesRequest;
import com.positivity.customer.internal.dto.UpdateContactRolesResponse;
import com.positivity.customer.internal.entity.ContactRole;
import com.positivity.customer.internal.entity.ContactRoleAssignment;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.ContactPointRepository;
import com.positivity.customer.internal.repository.ContactRoleAssignmentRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.service.ContactRoleService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
public class ContactRoleServiceImpl implements ContactRoleService {

    private static final Logger log = LoggerFactory.getLogger(ContactRoleServiceImpl.class);

    private final ContactRoleAssignmentRepository roleAssignmentRepository;
    private final CommercialPartyRepository partyRepository;
    private final PersonPartyRepository personRepository;
    private final ContactPointRepository contactPointRepository;
    private final PeopleClient peopleClient;

    ContactRoleServiceImpl(
            ContactRoleAssignmentRepository roleAssignmentRepository,
            CommercialPartyRepository partyRepository,
            PersonPartyRepository personRepository,
            ContactPointRepository contactPointRepository,
            PeopleClient peopleClient) {
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.partyRepository = partyRepository;
        this.personRepository = personRepository;
        this.contactPointRepository = contactPointRepository;
        this.peopleClient = peopleClient;
    }

    /**
     * Get all contacts with their role assignments for a party.
     *
     * @param partyId the party ID
     * @return response containing contacts with their roles
     * @throws IllegalArgumentException if party not found or invalid ID
     */
    @Override
    @NonNull
    @Transactional(readOnly = true)
    public GetContactsWithRolesResponse getContactsWithRoles(@NonNull UUID partyId) {
        // Verify party exists
        partyRepository
                .findById(partyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Party not found: " + partyId));

        // Get all role assignments for this party
        List<ContactRoleAssignment> assignments = roleAssignmentRepository.findByCustomerAccountId(partyId);

        // Group assignments by contact
        var contactMap = assignments.stream().collect(Collectors.groupingBy(ContactRoleAssignment::getContactId));

        // Names from pos-people (source of truth, ADR-0015 I2), batched by canonical id.
        Map<UUID, PeopleClient.PersonIdentity> identities =
                peopleClient.fetchPersonIdentitiesQuietly(contactMap.keySet());

        List<GetContactsWithRolesResponse.ContactWithRoles> contacts = new ArrayList<>();

        for (var entry : contactMap.entrySet()) {
            UUID contactId = entry.getKey();
            List<ContactRoleAssignment> contactAssignments = entry.getValue();

            // Get person details
            personRepository.findByPersonId(contactId).ifPresent(person -> {
                PeopleClient.PersonIdentity identity = identities.get(contactId);
                String contactName = identity != null && !identity.displayName().isBlank()
                        ? identity.displayName()
                        : person.getFirstName() + " " + person.getLastName();
                var contactDto = GetContactsWithRolesResponse.ContactWithRoles.builder()
                        .contactId(contactId.toString())
                        .contactName(contactName)
                        .build();

                // Email/phone from pos-people (source of truth, ADR-0015 I2);
                // fall back to the local contact_point copy when absent.
                String email = identity != null && !identity.emails().isEmpty()
                        ? identity.emails().get(0)
                        : Optional.ofNullable(
                                        contactPointRepository
                                                .findFirstByPersonPartyIdAndContactTypeAndIsPrimaryTrueOrderByCreatedAtAsc(
                                                        person.getPersonPartyId(),
                                                        com.positivity.customer.internal.enums.ContactPointType.EMAIL))
                                .map(cp -> cp.getValue())
                                .orElse(null);
                if (email != null) {
                    contactDto.setEmail(email);
                }
                contactDto.setHasPrimaryEmail(email != null);

                String phone = identity != null && !identity.phones().isEmpty()
                        ? identity.phones().get(0)
                        : Optional.ofNullable(
                                        contactPointRepository
                                                .findFirstByPersonPartyIdAndContactTypeAndIsPrimaryTrueOrderByCreatedAtAsc(
                                                        person.getPersonPartyId(),
                                                        com.positivity.customer.internal.enums.ContactPointType
                                                                .PHONE_MOBILE))
                                .map(cp -> cp.getValue())
                                .orElse(null);
                if (phone != null) {
                    contactDto.setPhone(phone);
                }

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
                .partyId(partyId.toString())
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
     * @param partyId   the party ID
     * @param contactId the contact (person) ID
     * @param request   the role assignment request
     * @return response with update status
     * @throws IllegalArgumentException if party/contact not found or invalid data
     */
    @Override
    @NonNull
    @Transactional
    public UpdateContactRolesResponse updateContactRoles(
            @NonNull UUID partyId, @NonNull UUID contactId, @NonNull UpdateContactRolesRequest request) {

        // Verify party exists
        partyRepository
                .findById(partyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Party not found: " + partyId));

        // Verify contact (person) exists
        personRepository
                .findByPersonId(contactId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Person not found for personId: " + contactId));

        // Delete existing role assignments for this contact/party
        roleAssignmentRepository.deleteByContactIdAndCustomerAccountId(contactId, partyId);
        roleAssignmentRepository.flush();

        // Create new assignments
        List<ContactRoleAssignment> newAssignments = new ArrayList<>();

        if (request.getRoles() != null) {
            for (var roleReq : request.getRoles()) {
                ContactRole role = ContactRole.valueOf(roleReq.getRoleCode());
                boolean isPrimary = Boolean.TRUE.equals(roleReq.getIsPrimary());

                // If marking as primary, demote existing primary for this role
                if (isPrimary) {
                    demoteExistingPrimary(partyId, role);
                }

                ContactRoleAssignment assignment = ContactRoleAssignment.builder()
                        .contactId(contactId)
                        .customerAccountId(partyId)
                        .roleName(role)
                        .primary(isPrimary)
                        .build();

                newAssignments.add(assignment);
            }
        }

        roleAssignmentRepository.saveAll(newAssignments);

        log.info(
                "Updated contact roles: partyId={}, contactId={}, roleCount={}",
                partyId,
                contactId,
                newAssignments.size());

        return UpdateContactRolesResponse.builder()
                .partyId(partyId.toString())
                .contactId(contactId.toString())
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
        roleAssignmentRepository
                .findByCustomerAccountIdAndRoleNameAndPrimaryTrue(customerAccountId, role)
                .ifPresent(existingPrimary -> {
                    existingPrimary.setPrimary(false);
                    roleAssignmentRepository.saveAndFlush(existingPrimary);
                    log.info("Demoted existing primary for role {} in account {}", role, customerAccountId);
                });
    }
}
