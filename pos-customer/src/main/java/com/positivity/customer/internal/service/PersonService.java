package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CreatePersonResponse;
import com.positivity.customer.internal.dto.GetPersonResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.server.ResponseStatusException;

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
