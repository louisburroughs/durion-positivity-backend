package com.positivity.customer.internal.service;

import com.positivity.customer.internal.client.PeopleClient;
import com.positivity.customer.internal.dto.CreatePartyRelationshipRequest;
import com.positivity.customer.internal.dto.CreatePartyRelationshipResponse;
import com.positivity.customer.internal.dto.GetCommercialAccountContactsResponse;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PartyRelationship;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.PartyRelationshipRole;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.service.PartyRelationshipService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service for managing party relationships between commercial accounts and
 * individuals.
 * Implements the business logic for Issue #110: Party: Associate Individuals to
 * Commercial Account.
 * <p>
 * Key Business Rules:
 * - Domain authority: CRM domain is the system of record for party
 * relationships
 * - Exactly one primary billing contact per commercial account at a time
 * - Assigning a new primary billing contact atomically demotes any existing
 * primary
 * - Effective dates determine active/inactive status; null end date means
 * active
 * </p>
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/110">Backend
 *      Issue #110</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyRelationshipServiceImpl implements PartyRelationshipService {

    private final PartyRelationshipRepository partyRelationshipRepository;
    private final CommercialPartyRepository partyRepository;
    private final PersonPartyRepository personRepository;
    private final PeopleClient peopleClient;
    private final Clock clock;

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
    @Override
    @Transactional
    public CreatePartyRelationshipResponse createRelationship(
            @NonNull UUID partyId, @NonNull CreatePartyRelationshipRequest request, UUID userId) {

        log.info(
                "Creating party relationship: partyId={}, personId={}, roles={}",
                partyId,
                request.getPersonId(),
                request.getRoles());

        // Validate party exists
        CommercialParty party = partyRepository
                .findById(partyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Party not found"));

        // Validate person exists
        PersonParty person = personRepository
                .findByPersonId(request.getPersonId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found"));

        // Check for overlapping relationships
        LocalDate today = LocalDate.now(clock);
        for (PartyRelationshipRole role : request.getRoles()) {
            List<PartyRelationship> overlapping = partyRelationshipRepository.findOverlappingRelationships(
                    partyId, person.getPersonPartyId(), role, today);
            if (!overlapping.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Active relationship already exists for party " + partyId + ", person " + request.getPersonId()
                                + ", role " + role);
            }
        }

        // Create relationship
        PartyRelationship relationship = new PartyRelationship();
        relationship.setFromParty(party);
        relationship.setToPerson(person);
        relationship.setRoles(request.getRoles());
        relationship.setEffectiveStartDate(request.getEffectiveStartDate());
        relationship.setEffectiveEndDate(request.getEffectiveEndDate());
        relationship.setCreatedBy(userId);
        relationship.setUpdatedBy(userId);

        boolean previousPrimaryDemoted = false;

        // Handle primary billing contact
        if (request.isPrimaryBillingContact()) {
            if (!request.getRoles().contains(PartyRelationshipRole.BILLING)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Primary billing contact must have BILLING role");
            }

            // Save first to get ID, then demote existing primary
            relationship.setPrimaryBillingContact(true);
            relationship = partyRelationshipRepository.save(relationship);

            // Demote existing primary billing contact
            int demoted = partyRelationshipRepository.demoteExistingPrimaryBillingContact(
                    partyId, relationship.getPartyRelationshipId());
            previousPrimaryDemoted = demoted > 0;

            if (previousPrimaryDemoted) {
                log.info("Demoted {} existing primary billing contact(s) for party {}", demoted, partyId);
            }
        } else {
            relationship = partyRelationshipRepository.save(relationship);
        }

        log.info(
                "Created party relationship {} for party {} and person {}",
                relationship.getPartyRelationshipId(),
                partyId,
                request.getPersonId());

        return CreatePartyRelationshipResponse.builder()
                .relationshipId(relationship.getPartyRelationshipId())
                .partyId(String.valueOf(partyId))
                .personId(request.getPersonId())
                .roles(relationship.getRoles())
                .isPrimaryBillingContact(relationship.isPrimaryBillingContact())
                .effectiveStartDate(relationship.getEffectiveStartDate())
                .effectiveEndDate(relationship.getEffectiveEndDate())
                .createdAt(relationship.getCreatedAt())
                .previousPrimaryDemoted(previousPrimaryDemoted)
                .build();
    }

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
    @Override
    @Transactional(readOnly = true)
    public GetCommercialAccountContactsResponse getContactsForCommercialAccount(
            @NonNull UUID partyId, List<PartyRelationshipRole> roles, String status) {

        log.debug("Getting contacts for commercial account: partyId={}, roles={}, status={}", partyId, roles, status);

        // Validate party exists in the unified party model (commercial + person).
        if (!partyRepository.existsById(partyId) && !personRepository.existsById(partyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Party not found");
        }

        LocalDate today = LocalDate.now(clock);
        List<PartyRelationship> relationships;

        if ("ACTIVE".equalsIgnoreCase(status) || status == null) {
            relationships = partyRelationshipRepository.findActiveByFromPartyId(partyId, today);
        } else {
            relationships = partyRelationshipRepository.findByFromPartyPartyId(partyId);
        }

        // Filter by roles if specified
        if (roles != null && !roles.isEmpty()) {
            relationships = relationships.stream()
                    .filter(rel -> rel.getRoles().stream().anyMatch(roles::contains))
                    .toList();
        }

        // Names from pos-people (source of truth, ADR-0015 I2), batched by canonical id.
        Map<UUID, PeopleClient.PersonIdentity> identities =
                peopleClient.fetchPersonIdentitiesQuietly(relationships.stream()
                        .map(rel -> rel.getToPerson().getPersonId())
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()));

        // Map to response DTOs
        List<GetCommercialAccountContactsResponse.ContactWithRole> contacts = relationships.stream()
                .map(rel -> {
                    PersonParty person = rel.getToPerson();
                    PeopleClient.PersonIdentity identity =
                            person.getPersonId() != null ? identities.get(person.getPersonId()) : null;

                    // Email/phone/name from pos-people (sole source of truth, ADR-0015 I2; issue #684).
                    String email = identity != null && !identity.emails().isEmpty()
                            ? identity.emails().get(0)
                            : null;
                    String phone = identity != null && !identity.phones().isEmpty()
                            ? identity.phones().get(0)
                            : null;
                    String displayName =
                            identity != null && !identity.displayName().isBlank() ? identity.displayName() : null;

                    return GetCommercialAccountContactsResponse.ContactWithRole.builder()
                            .relationshipId(rel.getPartyRelationshipId())
                            .individualId(person.getPersonId())
                            .roles(rel.getRoles())
                            .isPrimaryBilling(rel.isPrimaryBillingContact())
                            .status(rel.isActive(today) ? "ACTIVE" : "INACTIVE")
                            .effectiveFrom(rel.getEffectiveStartDate())
                            .effectiveTo(rel.getEffectiveEndDate())
                            .individual(GetCommercialAccountContactsResponse.IndividualDetails.builder()
                                    .displayName(displayName)
                                    .email(email)
                                    .phone(phone)
                                    .build())
                            .build();
                })
                .toList();

        return GetCommercialAccountContactsResponse.builder()
                .commercialAccountId(String.valueOf(partyId))
                .contacts(contacts)
                .build();
    }

    /**
     * Deactivates a party relationship by setting the effective end date to today.
     *
     * @param relationshipId the relationship ID
     * @param userId         the ID of the user deactivating the relationship
     */
    @Override
    @Transactional
    public void deactivateRelationship(@NonNull UUID relationshipId, UUID userId) {
        log.info("Deactivating relationship: {}", relationshipId);

        PartyRelationship relationship = partyRelationshipRepository
                .findById(relationshipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Relationship not found"));

        LocalDate today = LocalDate.now(clock);
        relationship.deactivate(today);
        relationship.setUpdatedBy(userId);
        partyRelationshipRepository.save(relationship);

        log.info(
                "Deactivated relationship {} for party {} and person {}",
                relationshipId,
                relationship.getFromParty().getPartyId(),
                relationship.getToPerson().getPersonId());
    }

    /**
     * Designates a new primary billing contact for a commercial account.
     * Atomically demotes any existing primary billing contact.
     *
     * @param partyId        the commercial account party ID
     * @param relationshipId the relationship to designate as primary
     * @param userId         the ID of the user making the change
     */
    @Override
    @Transactional
    public void designatePrimaryBillingContact(@NonNull UUID partyId, @NonNull UUID relationshipId, UUID userId) {

        log.info("Designating primary billing contact: partyId={}, relationshipId={}", partyId, relationshipId);

        PartyRelationship relationship = partyRelationshipRepository
                .findById(relationshipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Relationship not found"));

        if (!relationship.getFromParty().getPartyId().equals(partyId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Relationship does not belong to party " + partyId);
        }

        if (!relationship.hasRole(PartyRelationshipRole.BILLING)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Relationship must have BILLING role to be designated as primary billing contact");
        }

        LocalDate today = LocalDate.now(clock);
        if (!relationship.isActive(today)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Cannot designate inactive relationship as primary billing contact");
        }

        // Set as primary and demote existing
        relationship.setPrimaryBillingContact(true);
        relationship.setUpdatedBy(userId);
        partyRelationshipRepository.save(relationship);

        int demoted = partyRelationshipRepository.demoteExistingPrimaryBillingContact(partyId, relationshipId);
        log.info("Designated relationship {} as primary billing contact, demoted {} existing", relationshipId, demoted);
    }
}
