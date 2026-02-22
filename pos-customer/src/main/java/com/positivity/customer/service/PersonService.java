package com.positivity.customer.service;

import java.util.List;
import java.util.UUID;

import org.springframework.web.server.ResponseStatusException;

import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CreatePersonResponse;
import com.positivity.customer.internal.dto.GetPersonResponse;

public interface PersonService {

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
    CreatePersonResponse createPerson(CreatePersonRequest request, UUID userId);

    /**
     * Retrieves a person by ID.
     *
     * @param personId the person ID
     * @return person details
     * @throws ResponseStatusException if person not found
     */
    GetPersonResponse getPerson(UUID personId);

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
    List<GetPersonResponse> searchPersons(String name, String email, String phone, int limit, int offset);

    /**
     * Searches for persons by name (legacy method for backwards compatibility).
     *
     * @param searchTerm the search term
     * @return list of matching persons
     */
    List<GetPersonResponse> searchPersons(String searchTerm);

}