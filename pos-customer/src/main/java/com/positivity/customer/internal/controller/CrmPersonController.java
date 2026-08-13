package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CreatePersonResponse;
import com.positivity.customer.internal.dto.GetPersonResponse;
import com.positivity.customer.service.PersonService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing Person resources in the CRM domain.
 * <p>
 * Implements the following endpoints per Issue #111 (Create Individual Person
 * Record):
 * - POST /v1/crm/persons - Create a new person
 * - GET /v1/crm/persons/{personId} - Get a person by ID
 * - GET /v1/crm/persons - Search persons
 * </p>
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/111">Backend
 *      Issue #111</a>
 */
@Slf4j
@RestController
@RequestMapping("/v1/crm/persons")
@RequiredArgsConstructor
@Tag(name = "CRM Persons", description = "Individual person management APIs")
public class CrmPersonController {

    private final PersonService personService;

    /**
     * Creates a new individual person record.
     * <p>
     * Business Rules:
     * - At least one contact method (email or phone) required
     * - Phone numbers normalized to E.164 format
     * - Email addresses validated and stored lowercase
     * </p>
     *
     * @param request   the person creation request
     * @param principal the authenticated user
     * @return the created person with assigned ID
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"crm:person:create"})
    @PreAuthorize("hasAuthority('crm:person:create')")
    @EmitEvent(id = "CRM_PERSON_CREATE", apiVersion = "1")
    @Operation(operationId = "createCrmPerson", summary = "Create Individual Person Record", description = """
                    Creates an individual customer: the canonical person identity is resolved or created in \
                    pos-people (the source of truth for names and contact points), and a thin person-party \
                    link with a generated CUST-PER customer number is stored locally.
                    Use this tool when onboarding an individual customer; do not use \
                    createCrmCommercialAccount, which creates an organization, and note that if the identity \
                    already has a local person-party the existing record is returned instead of a duplicate.
                    Preconditions: none beyond authorization; contact points are validated before any \
                    identity is created so an invalid email persists nothing.
                    Required inputs: firstName, lastName, and preferredContactMethod (EMAIL, PHONE_CALL, \
                    SMS, or NONE); emails and phones are optional lists whose entries carry a value and an \
                    isPrimary flag, phone type defaults to PHONE_MOBILE, and emails are stored lowercase.
                    Emits a CRM_PERSON_CREATE event, publishes a party-changed customer fact, and writes \
                    the contact points to pos-people.
                    Returns 400 when firstName, lastName, or preferredContactMethod is missing or an email \
                    value is malformed.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Person created successfully",
            content = @Content(schema = @Schema(implementation = CreatePersonResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request - validation failed")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing required permission")
    public ResponseEntity<CreatePersonResponse> createCrmPerson(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The individual's name, preferred contact method, and contact points to register.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Individual with email and mobile",
                                                            value = """
                                                                    {"firstName":"Dana",
                                                                     "lastName":"Ortiz",
                                                                     "preferredContactMethod":"EMAIL",
                                                                     "emails":[{"value":"dana.ortiz@example.com","isPrimary":true}],
                                                                     "phones":[{"value":"+15125550142","type":"PHONE_MOBILE","isPrimary":true}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreatePersonRequest request,
            Principal principal) {

        log.info(
                "Creating person: firstName={}, lastName={}, user={}",
                request.getFirstName(),
                request.getLastName(),
                principal != null ? principal.getName() : "anonymous");

        UUID userId = extractUserId(principal);
        CreatePersonResponse response = personService.createPerson(request, userId);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Retrieves a person by their unique identifier.
     *
     * @param personId the person UUID
     * @return the person details
     */
    @GetMapping("/{personId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"crm:person:read"})
    @PreAuthorize("hasAuthority('crm:person:read')")
    @EmitEvent(id = "CRM_PERSON_GET", apiVersion = "1")
    @Operation(operationId = "getPerson", summary = "Get Person By Id", description = """
                    Returns the CRM view of an individual person by their canonical pos-people person id, \
                    including their customer number and preferred contact method.
                    Use this tool when the person id is already known; use searchPersons instead when \
                    locating a person by name or email.
                    Preconditions: a local person-party link must exist for the canonical person id.
                    Required inputs: personId (UUID, the canonical pos-people id) as a path parameter; \
                    there is no request body.
                    Emits a CRM_PERSON_GET audit event; no state changes occur.
                    Returns 404 when no person-party exists for the supplied personId.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Person found",
            content = @Content(schema = @Schema(implementation = GetPersonResponse.class)))
    @ApiResponse(responseCode = "404", description = "Person not found")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing required permission")
    public ResponseEntity<GetPersonResponse> getPerson(
            @Parameter(description = "The person's unique identifier") @PathVariable UUID personId) {

        log.debug("Getting person: {}", personId);

        GetPersonResponse response = personService.getPerson(personId);
        return ResponseEntity.ok(response);
    }

    /**
     * Searches for persons matching the given criteria.
     *
     * @param name   optional name search (searches first and last name)
     * @param email  optional email search
     * @param phone  optional phone number search
     * @param limit  maximum results to return (default 20)
     * @param offset starting offset for pagination (default 0)
     * @return list of matching persons
     */
    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"crm:person:read"})
    @PreAuthorize("hasAuthority('crm:person:read')")
    @EmitEvent(id = "CRM_PERSON_SEARCH", apiVersion = "1")
    @Operation(operationId = "searchPersons", summary = "Search Individual Persons", description = """
                    Searches individual customers by delegating the text query to pos-people, which matches \
                    first name, last name, primary email, and username, and returns only persons that also \
                    have a local CRM person-party.
                    Use this tool when locating an individual by name or email; use getPerson instead when \
                    the person id is already known, and note phone search is best-effort only because \
                    pos-people does not index phone numbers.
                    Preconditions: at least one of name, email, or phone should be supplied; when all are \
                    blank an empty list is returned without searching.
                    Required inputs: none individually; the first non-blank of name, email, and phone \
                    becomes the query, limit defaults to 20, and offset defaults to 0.
                    Emits a CRM_PERSON_SEARCH audit event; no state changes occur.
                    Returns 200 with an empty list rather than an error when nothing matches or the offset \
                    is beyond the result set.
                    """)
    @ApiResponse(responseCode = "200", description = "Search results returned")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing required permission")
    public ResponseEntity<List<GetPersonResponse>> searchPersons(
            @Parameter(description = "Search by name (first or last)") @RequestParam(required = false) String name,
            @Parameter(description = "Search by email address") @RequestParam(required = false) String email,
            @Parameter(description = "Search by phone number") @RequestParam(required = false) String phone,
            @Parameter(description = "Maximum results to return") @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "Starting offset for pagination") @RequestParam(defaultValue = "0") int offset) {

        log.debug(
                "Searching persons: name={}, email={}, phone={}, limit={}, offset={}",
                name,
                email,
                phone,
                limit,
                offset);

        List<GetPersonResponse> results = personService.searchPersons(name, email, phone, limit, offset);
        return ResponseEntity.ok(results);
    }

    private UUID extractUserId(Principal principal) {
        if (principal == null) {
            return null;
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException _) {
            log.warn("Unable to parse user ID from principal name: {}", principal.getName());
            return null;
        }
    }
}
