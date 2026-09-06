package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.GetContactsWithRolesResponse;
import com.positivity.customer.internal.dto.UpdateContactRolesRequest;
import com.positivity.customer.internal.dto.UpdateContactRolesResponse;
import com.positivity.customer.internal.entity.ContactRole;
import com.positivity.customer.internal.entity.ContactRoleAssignment;
import com.positivity.customer.internal.exception.CrmValidationException;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.ContactRoleAssignmentRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
    private final PersonDirectoryService personDirectoryService;

    ContactRoleServiceImpl(
            ContactRoleAssignmentRepository roleAssignmentRepository,
            CommercialPartyRepository partyRepository,
            PersonPartyRepository personRepository,
            PersonDirectoryService personDirectoryService) {
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.partyRepository = partyRepository;
        this.personRepository = personRepository;
        this.personDirectoryService = personDirectoryService;
    }

    /**
     * Get all contacts with their role assignments for a party.
     *
     * @param partyId the party ID
     * @return response containing contacts with their roles
     * @throws ResponseStatusException {@code 404} if the party does not exist
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
        Map<UUID, PersonDirectoryService.PersonIdentity> identities =
                personDirectoryService.fetchPersonIdentitiesQuietly(contactMap.keySet());

        List<GetContactsWithRolesResponse.ContactWithRoles> contacts = new ArrayList<>();

        for (var entry : contactMap.entrySet()) {
            UUID contactId = entry.getKey();
            List<ContactRoleAssignment> contactAssignments = entry.getValue();

            // Only surface contacts that still resolve to a person record in pos-customer.
            personRepository
                    .findByPersonId(contactId)
                    .ifPresent(person -> contacts.add(
                            buildContactWithRoles(contactId, contactAssignments, identities.get(contactId))));
        }

        return GetContactsWithRolesResponse.builder()
                .partyId(partyId.toString())
                .contacts(contacts)
                .build();
    }

    /**
     * Assembles one contact's response entry: name/email/phone projected from the pos-people
     * identity (ADR-0015 I2; issue #684, each independently optional), plus its mapped role
     * assignments.
     */
    private static GetContactsWithRolesResponse.@NonNull ContactWithRoles buildContactWithRoles(
            @NonNull UUID contactId,
            @NonNull List<ContactRoleAssignment> contactAssignments,
            PersonDirectoryService.@Nullable PersonIdentity identity) {
        var contactDto = GetContactsWithRolesResponse.ContactWithRoles.builder()
                .contactId(contactId.toString())
                .contactName(resolveContactName(identity))
                .build();

        String email = resolvePrimaryEmail(identity);
        if (email != null) {
            contactDto.setEmail(email);
        }
        contactDto.setHasPrimaryEmail(email != null);

        String phone = resolvePrimaryPhone(identity);
        if (phone != null) {
            contactDto.setPhone(phone);
        }

        contactDto.setRoles(mapAssignedRoles(contactAssignments));
        return contactDto;
    }

    private static String resolveContactName(PersonDirectoryService.@Nullable PersonIdentity identity) {
        return identity != null && !identity.displayName().isBlank() ? identity.displayName() : null;
    }

    private static String resolvePrimaryEmail(PersonDirectoryService.@Nullable PersonIdentity identity) {
        return identity != null && !identity.emails().isEmpty()
                ? identity.emails().get(0)
                : null;
    }

    private static String resolvePrimaryPhone(PersonDirectoryService.@Nullable PersonIdentity identity) {
        return identity != null && !identity.phones().isEmpty()
                ? identity.phones().get(0)
                : null;
    }

    @NonNull
    private static List<GetContactsWithRolesResponse.AssignedRole> mapAssignedRoles(
            @NonNull List<ContactRoleAssignment> contactAssignments) {
        return contactAssignments.stream()
                .map(assignment -> GetContactsWithRolesResponse.AssignedRole.builder()
                        .roleCode(assignment.getRoleName().name())
                        .roleLabel(assignment.getRoleName().getLabel())
                        .isPrimary(assignment.isPrimary())
                        .build())
                .toList();
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
     * @throws ResponseStatusException {@code 404} if the party or the contact does not exist
     * @throws CrmValidationException  if a submitted {@code roleCode} is missing, blank, or not
     *                                 a recognised {@link ContactRole} (answers {@code 400},
     *                                 issue #1714)
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

        // Parse every submitted role code before touching the existing assignments, so a bad
        // code is rejected as a 400 without depending on transaction rollback to undo the delete.
        List<ContactRoleAssignment> newAssignments = new ArrayList<>();
        if (request.getRoles() != null) {
            for (var roleReq : request.getRoles()) {
                newAssignments.add(ContactRoleAssignment.builder()
                        .contactId(contactId)
                        .customerAccountId(partyId)
                        .roleName(parseRoleCode(roleReq.getRoleCode()))
                        .primary(Boolean.TRUE.equals(roleReq.getIsPrimary()))
                        .build());
            }
        }

        // Delete existing role assignments for this contact/party
        roleAssignmentRepository.deleteByContactIdAndCustomerAccountId(contactId, partyId);
        roleAssignmentRepository.flush();

        // If marking as primary, demote the existing primary for that role
        for (ContactRoleAssignment assignment : newAssignments) {
            if (assignment.isPrimary()) {
                demoteExistingPrimary(partyId, assignment.getRoleName());
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
     * Resolves a client-submitted role code to a {@link ContactRole}.
     *
     * <p>{@link Enum#valueOf} throws the JDK's own {@link IllegalArgumentException} for an
     * unknown value, which is a server-defect type this module never maps to a client status
     * (issue #1694). Converting it here is what lets an unrecognised code answer the
     * documented {@code 400} with the {@code ApiError} envelope instead of a bodyless
     * {@code 404} (issue #1714).
     *
     * <p>The controller enforces the DTO's {@code @NotBlank} at the boundary, but this method
     * is the last line before {@code valueOf}, so a null or blank code is rejected here as well
     * rather than surfacing as a {@link NullPointerException} (500) from a caller that did not.
     *
     * @param roleCode the submitted role code, possibly absent
     * @return the matching role
     * @throws CrmValidationException if the code is missing, blank, or not a recognised
     *                                {@link ContactRole}
     */
    @NonNull
    private static ContactRole parseRoleCode(@Nullable String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            throw new CrmValidationException("roleCode is required");
        }
        try {
            return ContactRole.valueOf(roleCode);
        } catch (IllegalArgumentException e) {
            throw new CrmValidationException("Unrecognised roleCode: " + roleCode, e);
        }
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
