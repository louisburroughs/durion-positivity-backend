package com.positivity.customer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.positivity.customer.internal.dto.ContactPointType;
import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CreatePersonResponse;
import com.positivity.customer.internal.dto.GetPersonResponse;
import com.positivity.customer.internal.entity.ContactPoint;
import com.positivity.customer.internal.entity.Person;
import com.positivity.customer.internal.repository.ContactPointRepository;
import com.positivity.customer.internal.repository.PersonRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for managing individual person records in the CRM system.
 * Implements the business logic for Issue #111: Party: Create Individual Person
 * Record.
 * <p>
 * Domain Authority: CRM domain is the system of record for Party/Person
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
public class PersonService {

    private final PersonRepository personRepository;
    private final ContactPointRepository contactPointRepository;

    /**
     * Creates a new individual person record with optional contact points.
     * <p>
     * Acceptance Criteria (from Issue #111):
     * - AC1: Minimal create (name + preferred method) returns 201 and persists a
     * Person.
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
    @Transactional
    public CreatePersonResponse createPerson(@NonNull CreatePersonRequest request, UUID userId) {
        log.info("Creating person: firstName={}, lastName={}, preferredContactMethod={}, user={}",
                request.getFirstName(), request.getLastName(), request.getPreferredContactMethod(), userId);

        // Validation is handled by @Valid on controller, but double-check required
        // fields
        validateCreateRequest(request);

        // Create person entity
        Person person = new Person();
        person.setFirstName(request.getFirstName().trim());
        person.setLastName(request.getLastName().trim());
        person.setPreferredContactMethod(request.getPreferredContactMethod());

        // Save person first to get ID
        Person savedPerson = personRepository.save(person);
        log.debug("Person created with ID: {}", savedPerson.getPersonId());

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
                ContactPointType phoneType = phoneInput.getType() != null
                        ? phoneInput.getType()
                        : ContactPointType.PHONE_MOBILE;

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
            log.debug("Created {} contact points for person {}", contactPoints.size(), savedPerson.getPersonId());
        }

        log.info("Successfully created person {} with {} contact points",
                savedPerson.getPersonId(), contactPoints.size());

        return CreatePersonResponse.from(savedPerson, contactPoints.size());
    }

    /**
     * Retrieves a person by ID.
     *
     * @param personId the person ID
     * @return person details
     * @throws ResponseStatusException if person not found
     */
    @Transactional(readOnly = true)
    public GetPersonResponse getPerson(@NonNull UUID personId) {
        log.debug("Getting person: {}", personId);

        Person person = personRepository.findById(personId)
                .orElseThrow(() -> {
                    log.warn("Person not found: {}", personId);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found");
                });

        // Load contact points
        List<ContactPoint> contactPoints = contactPointRepository.findByPersonPersonId(personId);
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
    @Transactional(readOnly = true)
    public List<GetPersonResponse> searchPersons(String name, String email, String phone, int limit, int offset) {
        log.debug("Searching persons: name={}, email={}, phone={}, limit={}, offset={}",
                name, email, phone, limit, offset);

        List<Person> persons;

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
                    List<ContactPoint> contactPoints = contactPointRepository
                            .findByPersonPersonId(person.getPersonId());
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

    /**
     * Converts a Person entity to a GetPersonResponse DTO.
     *
     * @param person the person entity
     * @return the response DTO
     */
    private GetPersonResponse toGetPersonResponse(Person person) {
        List<GetPersonResponse.ContactPointDto> contactPointDtos = person.getContactPoints().stream()
                .map(cp -> GetPersonResponse.ContactPointDto.builder()
                        .contactPointId(cp.getContactPointId())
                        .contactType(cp.getContactType())
                        .value(cp.getValue())
                        .isPrimary(cp.isPrimary())
                        .build())
                .toList();

        return GetPersonResponse.builder()
                .personId(person.getPersonId())
                .firstName(person.getFirstName())
                .lastName(person.getLastName())
                .displayName(person.getDisplayName())
                .preferredContactMethod(person.getPreferredContactMethod())
                .contactPoints(contactPointDtos)
                .createdAt(person.getCreatedAt())
                .updatedAt(person.getUpdatedAt())
                .build();
    }
}
