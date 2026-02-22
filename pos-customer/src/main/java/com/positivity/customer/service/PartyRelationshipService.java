package com.positivity.customer.service;

import java.util.List;
import java.util.UUID;

import com.positivity.customer.internal.dto.CreatePartyRelationshipRequest;
import com.positivity.customer.internal.dto.CreatePartyRelationshipResponse;
import com.positivity.customer.internal.dto.GetCommercialAccountContactsResponse;
import com.positivity.customer.internal.enums.PartyRelationshipRole;

public interface PartyRelationshipService {

    /**
     * Creates a new party relationship between a commercial account and an
     * individual.
     * <p>
     * Business Rules:
     * - If isPrimaryBillingContact is true, atomically demotes any existing primary
     * billing contact
     * - Validates no overlapping active relationships for the same party pair and
     * role
     * </p>
     *
     * @param partyId the commercial account party ID
     * @param request the creation request
     * @param userId  the ID of the user creating the relationship
     * @return response with created relationship details
     */
    CreatePartyRelationshipResponse createRelationship(
            UUID partyId,
            CreatePartyRelationshipRequest request,
            UUID userId);

    /**
     * Gets all contacts with roles for a commercial account.
     * Implements the consumer API contract: GET
     * /crm/commercial-accounts/{id}/contacts
     *
     * @param partyId the commercial account party ID
     * @param roles   optional filter by roles
     * @param status  optional filter by status (ACTIVE or INACTIVE)
     * @return contacts with their roles
     */
    GetCommercialAccountContactsResponse getContactsForCommercialAccount(
            UUID partyId,
            List<PartyRelationshipRole> roles,
            String status);

    /**
     * Deactivates a party relationship by setting the effective end date to today.
     *
     * @param relationshipId the relationship ID
     * @param userId         the ID of the user deactivating the relationship
     */
    void deactivateRelationship(UUID relationshipId, UUID userId);

    /**
     * Designates a new primary billing contact for a commercial account.
     * Atomically demotes any existing primary billing contact.
     *
     * @param partyId        the commercial account party ID
     * @param relationshipId the relationship to designate as primary
     * @param userId         the ID of the user making the change
     */
    void designatePrimaryBillingContact(
            UUID partyId,
            UUID relationshipId,
            UUID userId);

}