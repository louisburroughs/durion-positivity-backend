package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.GetContactsWithRolesResponse;
import com.positivity.customer.internal.dto.UpdateContactRolesRequest;
import com.positivity.customer.internal.dto.UpdateContactRolesResponse;
import java.util.UUID;

public interface ContactRoleService {

    /**
     * Get all contacts with their role assignments for a party.
     *
     * @param partyId the party ID
     * @return response containing contacts with their roles
     * @throws org.springframework.web.server.ResponseStatusException {@code 404} if the party
     *                                                                does not exist
     */
    GetContactsWithRolesResponse getContactsWithRoles(UUID partyId);

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
     * @throws org.springframework.web.server.ResponseStatusException {@code 404} if the party
     *                                                                or the contact does not exist
     * @throws com.positivity.customer.internal.exception.CrmValidationException if a submitted
     *                                                                {@code roleCode} is not a
     *                                                                recognised role ({@code 400})
     */
    UpdateContactRolesResponse updateContactRoles(UUID partyId, UUID contactId, UpdateContactRolesRequest request);
}
