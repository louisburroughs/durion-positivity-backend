package com.positivity.customer.service;

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
     * @throws IllegalArgumentException if party not found or invalid ID
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
     * @throws IllegalArgumentException if party/contact not found or invalid data
     */
    UpdateContactRolesResponse updateContactRoles(UUID partyId, UUID contactId, UpdateContactRolesRequest request);
}
