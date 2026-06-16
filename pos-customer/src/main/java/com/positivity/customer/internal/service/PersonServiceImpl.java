package com.positivity.customer.internal.service;

import com.positivity.customer.internal.client.PeopleClient;
import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CreatePersonResponse;
import com.positivity.customer.internal.dto.GetPersonResponse;
import com.positivity.customer.internal.entity.ContactPoint;
import com.positivity.customer.internal.entity.PartyRelationship;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.ContactPointType;
import com.positivity.customer.internal.repository.ContactPointRepository;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.service.PersonService;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service for managing individual person records in the CRM system.
 * Implements the business logic for Issue #111: Party: Create Individual Person
 * Record.
 * <p>
 * Domain Authority: CRM domain is the system of record for Party/PersonParty
 * entities.
 * </p>
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/111">Backend
 *      Issue #111</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonServiceImpl implements PersonService {

    private final PersonPartyRepository personRepository;
    private final ContactPointRepository contactPointRepository;
    private final PartyRelationshipRepository partyRelationshipRepository;
    private final PeopleClient peopleClient;
    private final Clock clock;

    /**
     * Creates a new individual person record with optional contact points.
     * <p>
     * Acceptance Criteria (from Issue #111):
     * - AC1: Minimal create (name + preferred method) returns 201 and persists a
     * PersonParty.
     * - AC2: Create with two emails and two phone numbers persists four
     * ContactPoints.
     * - AC3: Missing lastName returns 400 and persists nothing.
     * - AC4: Invalid email format returns 400 and persists nothing.
     * </p>
     *
     * @param request the creation request
     * @param userId  the ID of the user creating the person (may be null)
     * @return response with created person details
     * @throws ResponseStatusException if validation fails
     */
    @Override
    @Transactional
    public CreatePersonResponse createPerson(@NonNull CreatePersonRequest request, UUID userId) {
        log.info(
                "Creating person: firstName={}, lastName={}, preferredContactMethod={}, user={}",
                request.getFirstName(),
                request.getLastName(),
                request.getPreferredContactMethod(),
                userId);

        // Validation is handled by @Valid on controller, but double-check required
        // fields
        validateCreateRequest(request);

        String primaryEmail = extractPrimaryEmail(request);
        String primaryPhone = extractPrimaryPhone(request);
        UUID peoplePersonId = peopleClient.resolveOrCreatePersonId(
                primaryEmail, primaryPhone, request.getLastName(), request.getFirstName());

        // Reuse existing person-party if already associated to this canonical person
        PersonParty existing = personRepository.findByPersonId(peoplePersonId).orElse(null);
        if (existing != null) {
            int existingContactPointCount = contactPointRepository
                    .findByPersonPartyId(existing.getPersonPartyId())
                    .size();
            return CreatePersonResponse.from(existing, existingContactPointCount);
        }

        // Create person-party entity
        PersonParty person = new PersonParty();
        person.setFirstName(request.getFirstName().trim());
        person.setLastName(request.getLastName().trim());
        person.setPersonId(peoplePersonId);
        person.setCustomerNumber("CUST-PER-" + UUIDv7Generator.generate());
        person.setPreferredContactMethod(request.getPreferredContactMethod());

        // Save person first to get ID
        PersonParty savedPerson = personRepository.save(person);
        log.debug(
                "PersonParty created with partyId={} mapped to personId={}",
                savedPerson.getPersonPartyId(),
                savedPerson.getPersonId());

        // Create contact points
        List<ContactPoint> contactPoints = new ArrayList<>();

        // Process emails
        if (request.getEmails() != null && !request.getEmails().isEmpty()) {
            for (CreatePersonRequest.EmailInput emailInput : request.getEmails()) {
                validateEmail(emailInput.getValue());

                ContactPoint cp = new ContactPoint();
                cp.setPerson(savedPerson);
                cp.setContactType(ContactPointType.EMAIL);
                cp.setValue(emailInput.getValue().trim().toLowerCase());
                cp.setPrimary(emailInput.isPrimary());
                contactPoints.add(cp);
            }
        }

        // Process phones
        if (request.getPhones() != null && !request.getPhones().isEmpty()) {
            for (CreatePersonRequest.PhoneInput phoneInput : request.getPhones()) {
                ContactPointType phoneType =
                        phoneInput.getType() != null ? phoneInput.getType() : ContactPointType.PHONE_MOBILE;

                ContactPoint cp = new ContactPoint();
                cp.setPerson(savedPerson);
                cp.setContactType(phoneType);
                cp.setValue(phoneInput.getValue().trim());
                cp.setPrimary(phoneInput.isPrimary());
                contactPoints.add(cp);
            }
        }

        // Save all contact points
        if (!contactPoints.isEmpty()) {
            contactPointRepository.saveAll(contactPoints);
            log.debug(
                    "Created {} contact points for personPartyId={}",
                    contactPoints.size(),
                    savedPerson.getPersonPartyId());
        }

        log.info(
                "Successfully created person personId={} personPartyId={} with {} contact points",
                savedPerson.getPersonId(),
                savedPerson.getPersonPartyId(),
                contactPoints.size());

        return CreatePersonResponse.from(savedPerson, contactPoints.size());
    }

    /**
     * Retrieves a person by ID.
     *
     * @param personId the person ID
     * @return person details
     * @throws ResponseStatusException if person not found
     */
    @Override
    @Transactional(readOnly = true)
    public GetPersonResponse getPerson(@NonNull UUID personId) {
        log.debug("Getting person: {}", personId);

        PersonParty person = personRepository.findByPersonId(personId).orElseThrow(() -> {
            log.warn("Person not found: {}", personId);
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found");
        });

        // Load contact points
        List<ContactPoint> contactPoints = contactPointRepository.findByPersonPartyId(person.getPersonPartyId());
        person.getContactPoints().addAll(contactPoints);

        return toGetPersonResponse(person);
    }

    /**
     * Searches for persons by various criteria.
     *
     * @param name   optional name search term (first or last name)
     * @param email  optional email search term
     * @param phone  optional phone search term
     * @param limit  maximum results to return
     * @param offset starting offset for pagination
     * @return list of matching persons
     */
    @Override
    @Transactional(readOnly = true)
    public List<GetPersonResponse> searchPersons(String name, String email, String phone, int limit, int offset) {
        log.debug(
                "Searching persons: name={}, email={}, phone={}, limit={}, offset={}",
                name,
                email,
                phone,
                limit,
                offset);

        List<PersonParty> persons;

        // Search by different criteria
        if (name != null && !name.trim().isEmpty()) {
            persons = personRepository.searchByName(name.trim());
        } else if (email != null && !email.trim().isEmpty()) {
            // Find by email requires joining with contact points
            persons = personRepository.findByContactPointValue(email.trim().toLowerCase());
        } else if (phone != null && !phone.trim().isEmpty()) {
            // Find by phone requires joining with contact points
            persons = personRepository.findByContactPointValue(phone.trim());
        } else {
            // Return empty for no search criteria
            persons = new ArrayList<>();
        }

        // Apply pagination manually (for now - could be improved with Pageable)
        int endIndex = Math.min(offset + limit, persons.size());
        if (offset >= persons.size()) {
            return new ArrayList<>();
        }
        persons = persons.subList(offset, endIndex);

        return persons.stream()
                .map(person -> {
                    List<ContactPoint> contactPoints =
                            contactPointRepository.findByPersonPartyId(person.getPersonPartyId());
                    person.getContactPoints().addAll(contactPoints);
                    return toGetPersonResponse(person);
                })
                .toList();
    }

    /**
     * Searches for persons by name (legacy method for backwards compatibility).
     *
     * @param searchTerm the search term
     * @return list of matching persons
     */
    @Override
    @Transactional(readOnly = true)
    public List<GetPersonResponse> searchPersons(@NonNull String searchTerm) {
        return searchPersons(searchTerm, null, null, 20, 0);
    }

    private void validateCreateRequest(CreatePersonRequest request) {
        if (request.getFirstName() == null || request.getFirstName().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "firstName is required");
        }
        if (request.getLastName() == null || request.getLastName().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lastName is required");
        }
        if (request.getPreferredContactMethod() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "preferredContactMethod is required");
        }
    }

    private void validateEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email value is required");
        }
        // Basic email format validation
        if (!email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid email format: " + email);
        }
    }

    private String extractPrimaryEmail(CreatePersonRequest request) {
        if (request.getEmails() == null || request.getEmails().isEmpty()) {
            return null;
        }
        return request.getEmails().stream()
                .filter(CreatePersonRequest.EmailInput::isPrimary)
                .map(CreatePersonRequest.EmailInput::getValue)
                .findFirst()
                .orElse(request.getEmails().get(0).getValue());
    }

    private String extractPrimaryPhone(CreatePersonRequest request) {
        if (request.getPhones() == null || request.getPhones().isEmpty()) {
            return null;
        }
        return request.getPhones().stream()
                .filter(CreatePersonRequest.PhoneInput::isPrimary)
                .map(CreatePersonRequest.PhoneInput::getValue)
                .findFirst()
                .orElse(request.getPhones().get(0).getValue());
    }

    /**
     * Converts a PersonParty entity to a GetPersonResponse DTO.
     *
     * @param person the person-party entity
     * @return the response DTO
     */
    private GetPersonResponse toGetPersonResponse(PersonParty person) {
        List<PartyRelationship> commercialRelationships = partyRelationshipRepository.findActiveByToPersonPartyId(
                person.getPersonPartyId(), LocalDate.now(clock));
        int commercialAccountCount = (int) commercialRelationships.stream()
                .map(rel -> rel.getFromParty().getPartyId())
                .distinct()
                .count();

        // Name from pos-people (source of truth, ADR-0015 I2); fall back to the local
        // person_party copy while the thin-link migration is in progress.
        PeopleClient.PersonIdentity identity = person.getPersonId() != null
                ? peopleClient
                        .fetchPersonIdentitiesQuietly(Set.of(person.getPersonId()))
                        .get(person.getPersonId())
                : null;
        String firstName =
                identity != null && identity.firstName() != null ? identity.firstName() : person.getFirstName();
        String lastName = identity != null && identity.lastName() != null ? identity.lastName() : person.getLastName();
        String displayName = identity != null && !identity.displayName().isBlank()
                ? identity.displayName()
                : person.getDisplayName();

        // Contact points from pos-people (source of truth, ADR-0015 I2); fall back to
        // the local contact_point copy when pos-people has none for this person.
        List<GetPersonResponse.ContactPointDto> contactPointDtos;
        if (identity != null && !identity.contactPoints().isEmpty()) {
            contactPointDtos = identity.contactPoints().stream()
                    .map(cp -> GetPersonResponse.ContactPointDto.builder()
                            .contactType(parseContactType(cp.contactType()))
                            .value(cp.value())
                            .isPrimary(cp.isPrimary())
                            .build())
                    .toList();
        } else {
            contactPointDtos = person.getContactPoints().stream()
                    .map(cp -> GetPersonResponse.ContactPointDto.builder()
                            .contactPointId(cp.getContactPointId())
                            .contactType(cp.getContactType())
                            .value(cp.getValue())
                            .isPrimary(cp.isPrimary())
                            .build())
                    .toList();
        }

        return GetPersonResponse.builder()
                .personId(person.getPersonId())
                .firstName(firstName)
                .lastName(lastName)
                .displayName(displayName)
                .preferredContactMethod(person.getPreferredContactMethod())
                .contactPoints(contactPointDtos)
                .individualCustomer(true)
                .commercialContact(!commercialRelationships.isEmpty())
                .commercialAccountCount(commercialAccountCount)
                .createdAt(person.getCreatedAt())
                .updatedAt(person.getUpdatedAt())
                .build();
    }

    /** Maps a pos-people contact-type string to the local enum; null if unrecognized. */
    private static ContactPointType parseContactType(String type) {
        if (type == null) {
            return null;
        }
        try {
            return ContactPointType.valueOf(type);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
