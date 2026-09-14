package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CreatePersonResponse;
import com.positivity.customer.internal.dto.GetPersonResponse;
import com.positivity.customer.internal.entity.PartyRelationship;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.ContactPointType;
import com.positivity.customer.internal.exception.CrmDuplicateResourceException;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final PartyRelationshipRepository partyRelationshipRepository;
    private final PersonDirectoryService personDirectoryService;
    private final Clock clock;
    private final CustomerFactPublisher customerFactPublisher;

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
     * <p>
     * A request carrying a customerNumber claims it as the party's business key: the number is
     * kept as given, and a number already in use is refused as a duplicate rather than turned
     * into a second party for the same customer (issue #1978). Omit it and one is generated.
     * </p>
     *
     * @param request the creation request
     * @param userId  the ID of the user creating the person (may be null)
     * @return response with created person details
     * @throws ResponseStatusException if validation fails
     * @throws com.positivity.customer.internal.exception.CrmDuplicateResourceException if the
     *     request's customerNumber already belongs to another party
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

        String firstName = request.getFirstName().trim();
        String lastName = request.getLastName().trim();

        // Build contact-point upserts from the request first, validating eagerly so an
        // invalid email fails BEFORE we resolve/create a canonical person in pos-people
        // (the sole writer of identity now) — AC4 must persist nothing anywhere.
        // pos-people is the source of truth for contacts (ADR-0015 I2); pos-customer
        // keeps no local copy (issue #684).
        List<PersonDirectoryService.ContactPointUpsert> contactPoints = new ArrayList<>();
        if (request.getEmails() != null) {
            for (CreatePersonRequest.EmailInput emailInput : request.getEmails()) {
                validateEmail(emailInput.getValue());
                contactPoints.add(new PersonDirectoryService.ContactPointUpsert(
                        ContactPointType.EMAIL.name(),
                        emailInput.getValue().trim().toLowerCase(),
                        emailInput.isPrimary()));
            }
        }
        if (request.getPhones() != null) {
            for (CreatePersonRequest.PhoneInput phoneInput : request.getPhones()) {
                ContactPointType phoneType =
                        phoneInput.getType() != null ? phoneInput.getType() : ContactPointType.PHONE_MOBILE;
                contactPoints.add(new PersonDirectoryService.ContactPointUpsert(
                        phoneType.name(), phoneInput.getValue().trim(), phoneInput.isPrimary()));
            }
        }

        // A caller-supplied customer number is the party's business key, so it is checked
        // before anything is written: a repeat of the same import must be refused, not turned
        // into a second party for one real customer (issue #1978).
        String customerNumber = trimToNull(request.getCustomerNumber());
        if (customerNumber != null
                && personRepository.findByCustomerNumber(customerNumber).isPresent()) {
            throw new CrmDuplicateResourceException("Customer", customerNumber);
        }

        String primaryEmail = extractPrimaryEmail(request);
        String primaryPhone = extractPrimaryPhone(request);
        UUID peoplePersonId =
                personDirectoryService.resolveOrCreatePersonId(primaryEmail, primaryPhone, lastName, firstName);

        // Reuse existing person-party if already associated to this canonical person
        PersonParty existing = personRepository.findByPersonId(peoplePersonId).orElse(null);
        if (existing != null) {
            return CreatePersonResponse.from(existing, firstName, lastName, 0);
        }

        // Create the thin link (no local name/contact copy)
        PersonParty person = new PersonParty();
        person.setPersonId(peoplePersonId);
        person.setCustomerNumber(customerNumber != null ? customerNumber : "CUST-PER-" + UUIDv7Generator.generate());
        person.setPreferredContactMethod(request.getPreferredContactMethod());
        PersonParty savedPerson = savePerson(person, customerNumber);
        customerFactPublisher.partyChanged(savedPerson);
        log.debug(
                "PersonParty created with partyId={} mapped to personId={}",
                savedPerson.getPersonPartyId(),
                savedPerson.getPersonId());

        // Write contact points to pos-people (source of truth, ADR-0015 I2). The names go with
        // them: the command is a full-attribute upsert, and a person created a moment ago is not
        // in the replica it would otherwise read them from (issue #1977).
        personDirectoryService.setContactPoints(peoplePersonId, contactPoints, firstName, lastName);

        log.info(
                "Successfully created person personId={} personPartyId={} with {} contact points",
                savedPerson.getPersonId(),
                savedPerson.getPersonPartyId(),
                contactPoints.size());

        return CreatePersonResponse.from(savedPerson, firstName, lastName, contactPoints.size());
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

        // Identity (name/email) now lives solely in pos-people (ADR-0015 I2, issue #684).
        // Delegate the text search to pos-people (GET /v1/people?q=, which matches
        // firstName, lastName, primaryEmail, username), then keep only canonical
        // persons that have a local CRM person-party so we can attach CRM-local state.
        // NOTE: phone search is best-effort only — pos-people's q does not index phone
        // numbers, so a phone-only query typically returns no matches. A dedicated
        // pos-people phone/contact-point lookup would be needed to restore it.
        String query = firstNonBlank(name, email, phone);
        if (query == null) {
            return new ArrayList<>();
        }

        List<GetPersonResponse> matches = personDirectoryService.searchPersons(query).stream()
                .map(identity -> personRepository
                        .findByPersonId(identity.id())
                        .map(person -> toGetPersonResponse(person, identity))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();

        // Apply pagination manually (for now - could be improved with Pageable)
        if (offset >= matches.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(matches.subList(offset, Math.min(offset + limit, matches.size())));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return null;
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

    /**
     * Saves the new party, flushing so a {@code (tenant_id, customer_number)} conflict surfaces
     * here rather than at commit.
     *
     * <p>The duplicate pre-check above is not atomic with this insert, so a concurrent create
     * quoting the same number reaches the constraint. Left as a raw
     * {@code DataIntegrityViolationException} it would escape the bulk ingest's rejection types
     * and be reported as INTERNAL_ERROR instead of the duplicate the row actually is, and the
     * interactive caller would get a 500 rather than a 409.
     */
    private PersonParty savePerson(PersonParty person, String customerNumber) {
        try {
            return personRepository.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            if (customerNumber != null) {
                log.warn("Customer number '{}' was taken concurrently", customerNumber, e);
                throw new CrmDuplicateResourceException("Customer", customerNumber);
            }
            throw e;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
        PersonDirectoryService.PersonIdentity identity = person.getPersonId() != null
                ? personDirectoryService
                        .fetchPersonIdentitiesQuietly(Set.of(person.getPersonId()))
                        .get(person.getPersonId())
                : null;
        return toGetPersonResponse(person, identity);
    }

    /**
     * Builds the response from the CRM person-party plus a canonical identity already
     * fetched from pos-people (the sole source for name/contacts, ADR-0015 I2). When
     * {@code identity} is null (pos-people unreachable or no match) names and contacts
     * are absent — there is no local fallback after issue #684.
     */
    private GetPersonResponse toGetPersonResponse(PersonParty person, PersonDirectoryService.PersonIdentity identity) {
        List<PartyRelationship> commercialRelationships = partyRelationshipRepository.findActiveByToPersonPartyId(
                person.getPersonPartyId(), LocalDate.now(clock));
        int commercialAccountCount = (int) commercialRelationships.stream()
                .map(rel -> rel.getFromParty().getPartyId())
                .distinct()
                .count();

        String firstName = identity != null ? identity.firstName() : null;
        String lastName = identity != null ? identity.lastName() : null;
        String displayName = identity != null && !identity.displayName().isBlank() ? identity.displayName() : null;

        List<GetPersonResponse.ContactPointDto> contactPointDtos = identity == null
                ? List.of()
                : identity.contactPoints().stream()
                        .map(cp -> GetPersonResponse.ContactPointDto.builder()
                                .contactType(parseContactType(cp.contactType()))
                                .value(cp.value())
                                .isPrimary(cp.isPrimary())
                                .build())
                        .toList();

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
