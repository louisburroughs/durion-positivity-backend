package com.positivity.customer.service;

import com.positivity.customer.internal.dto.ContactPointType;
import com.positivity.customer.internal.dto.CreatePartyRelationshipRequest;
import com.positivity.customer.internal.dto.CreatePartyRelationshipResponse;
import com.positivity.customer.internal.dto.GetCommercialAccountContactsResponse;
import com.positivity.customer.internal.dto.PartyRelationshipRole;
import com.positivity.customer.internal.entity.ContactPoint;
import com.positivity.customer.internal.entity.Party;
import com.positivity.customer.internal.entity.PartyRelationship;
import com.positivity.customer.internal.entity.Person;
import com.positivity.customer.internal.repository.ContactPointRepository;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PartyRepository;
import com.positivity.customer.internal.repository.PersonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
public class PartyRelationshipService {

        private final PartyRelationshipRepository partyRelationshipRepository;
        private final PartyRepository partyRepository;
        private final PersonRepository personRepository;
        private final ContactPointRepository contactPointRepository;

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
        @Transactional
        public CreatePartyRelationshipResponse createRelationship(
                        @NonNull UUID partyId,
                        @NonNull CreatePartyRelationshipRequest request,
                        UUID userId) {

                log.info("Creating party relationship: partyId={}, personId={}, roles={}",
                                partyId, request.getPersonId(), request.getRoles());

                // Validate party exists
                Party party = partyRepository.findById(partyId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Party not found"));

                // Validate person exists
                Person person = personRepository.findById(request.getPersonId())
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Person not found"));

                // Check for overlapping relationships
                LocalDate today = LocalDate.now();
                for (PartyRelationshipRole role : request.getRoles()) {
                        List<PartyRelationship> overlapping = partyRelationshipRepository.findOverlappingRelationships(
                                        partyId, request.getPersonId(), role, today);
                        if (!overlapping.isEmpty()) {
                                throw new ResponseStatusException(HttpStatus.CONFLICT,
                                                "Active relationship already exists for party " + partyId +
                                                                ", person " + request.getPersonId() + ", role " + role);
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
                                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                                "Primary billing contact must have BILLING role");
                        }

                        // Save first to get ID, then demote existing primary
                        relationship.setPrimaryBillingContact(true);
                        relationship = partyRelationshipRepository.save(relationship);

                        // Demote existing primary billing contact
                        int demoted = partyRelationshipRepository.demoteExistingPrimaryBillingContact(
                                        partyId, relationship.getPartyRelationshipId());
                        previousPrimaryDemoted = demoted > 0;

                        if (previousPrimaryDemoted) {
                                log.info("Demoted {} existing primary billing contact(s) for party {}", demoted,
                                                partyId);
                        }
                } else {
                        relationship = partyRelationshipRepository.save(relationship);
                }

                log.info("Created party relationship {} for party {} and person {}",
                                relationship.getPartyRelationshipId(), partyId, request.getPersonId());

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
        @Transactional(readOnly = true)
        public GetCommercialAccountContactsResponse getContactsForCommercialAccount(
                        @NonNull UUID partyId,
                        List<PartyRelationshipRole> roles,
                        String status) {

                log.debug("Getting contacts for commercial account: partyId={}, roles={}, status={}",
                                partyId, roles, status);

                // Validate party exists
                if (!partyRepository.existsById(partyId)) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Party not found");
                }

                LocalDate today = LocalDate.now();
                List<PartyRelationship> relationships;

                if ("ACTIVE".equalsIgnoreCase(status) || status == null) {
                        relationships = partyRelationshipRepository.findActiveByFromPartyId(partyId, today);
                } else {
                        relationships = partyRelationshipRepository.findByFromPartyId(partyId);
                }

                // Filter by roles if specified
                if (roles != null && !roles.isEmpty()) {
                        relationships = relationships.stream()
                                        .filter(rel -> rel.getRoles().stream().anyMatch(roles::contains))
                                        .toList();
                }

                // Map to response DTOs
                List<GetCommercialAccountContactsResponse.ContactWithRole> contacts = relationships.stream()
                                .map(rel -> {
                                        Person person = rel.getToPerson();
                                        // Get primary contact points for the person
                                        String email = getPrimaryContactValue(person.getPersonId(),
                                                        ContactPointType.EMAIL);
                                        String phone = getPrimaryContactValue(person.getPersonId(),
                                                        ContactPointType.PHONE_MOBILE);
                                        if (phone == null) {
                                                phone = getPrimaryContactValue(person.getPersonId(),
                                                                ContactPointType.PHONE_WORK);
                                        }

                                        return GetCommercialAccountContactsResponse.ContactWithRole.builder()
                                                        .relationshipId(rel.getPartyRelationshipId())
                                                        .individualId(person.getPersonId())
                                                        .roles(rel.getRoles())
                                                        .isPrimaryBilling(rel.isPrimaryBillingContact())
                                                        .status(rel.isActive() ? "ACTIVE" : "INACTIVE")
                                                        .effectiveFrom(rel.getEffectiveStartDate())
                                                        .effectiveTo(rel.getEffectiveEndDate())
                                                        .individual(GetCommercialAccountContactsResponse.IndividualDetails
                                                                        .builder()
                                                                        .displayName(person.getDisplayName())
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
        @Transactional
        public void deactivateRelationship(@NonNull UUID relationshipId, UUID userId) {
                log.info("Deactivating relationship: {}", relationshipId);

                PartyRelationship relationship = partyRelationshipRepository.findById(relationshipId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Relationship not found"));

                relationship.deactivate();
                relationship.setUpdatedBy(userId);
                partyRelationshipRepository.save(relationship);

                log.info("Deactivated relationship {} for party {} and person {}",
                                relationshipId, relationship.getFromParty().getId(),
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
        @Transactional
        public void designatePrimaryBillingContact(
                        @NonNull UUID partyId,
                        @NonNull UUID relationshipId,
                        UUID userId) {

                log.info("Designating primary billing contact: partyId={}, relationshipId={}", partyId, relationshipId);

                PartyRelationship relationship = partyRelationshipRepository.findById(relationshipId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Relationship not found"));

                if (!relationship.getFromParty().getId().equals(partyId)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Relationship does not belong to party " + partyId);
                }

                if (!relationship.hasRole(PartyRelationshipRole.BILLING)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Relationship must have BILLING role to be designated as primary billing contact");
                }

                if (!relationship.isActive()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Cannot designate inactive relationship as primary billing contact");
                }

                // Set as primary and demote existing
                relationship.setPrimaryBillingContact(true);
                relationship.setUpdatedBy(userId);
                partyRelationshipRepository.save(relationship);

                int demoted = partyRelationshipRepository.demoteExistingPrimaryBillingContact(partyId, relationshipId);
                log.info("Designated relationship {} as primary billing contact, demoted {} existing", relationshipId,
                                demoted);
        }

        private String getPrimaryContactValue(UUID personId, ContactPointType type) {
                ContactPoint primary = contactPointRepository.findByPersonPersonIdAndContactTypeAndIsPrimaryTrue(
                                personId,
                                type);
                if (primary != null) {
                        return primary.getValue();
                }
                // Fall back to first of that type
                List<ContactPoint> contacts = contactPointRepository.findByPersonPersonIdAndContactType(personId, type);
                return contacts.isEmpty() ? null : contacts.get(0).getValue();
        }
}
