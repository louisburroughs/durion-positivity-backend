package com.positivity.customer.internal.controller;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

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

import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CreatePersonResponse;
import com.positivity.customer.internal.dto.GetPersonResponse;
import com.positivity.customer.service.PersonService;
import com.positivity.events.EmitEvent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    @PreAuthorize("hasAuthority('crm:person:create')")
    @EmitEvent(id = "CRM_PERSON_CREATE", apiVersion = "1")
    @Operation(summary = "Create a new person", description = "Creates an individual person record in the CRM system")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Person created successfully", content = @Content(schema = @Schema(implementation = CreatePersonResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request - validation failed"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden - missing required permission")
    })
    public ResponseEntity<CreatePersonResponse> createPerson(
            @Valid @RequestBody CreatePersonRequest request,
            Principal principal) {

        log.info("Creating person: firstName={}, lastName={}, user={}",
                request.getFirstName(), request.getLastName(),
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
    @PreAuthorize("hasAuthority('crm:person:read')")
    @EmitEvent(id = "CRM_PERSON_GET", apiVersion = "1")
    @Operation(summary = "Get a person by ID", description = "Retrieves an individual person record by their unique identifier")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Person found", content = @Content(schema = @Schema(implementation = GetPersonResponse.class))),
            @ApiResponse(responseCode = "404", description = "Person not found"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden - missing required permission")
    })
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
    @PreAuthorize("hasAuthority('crm:person:read')")
    @EmitEvent(id = "CRM_PERSON_SEARCH", apiVersion = "1")
    @Operation(summary = "Search persons", description = "Searches for persons matching the specified criteria")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Search results returned"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden - missing required permission")
    })
    public ResponseEntity<List<GetPersonResponse>> searchPersons(
            @Parameter(description = "Search by name (first or last)") @RequestParam(required = false) String name,
            @Parameter(description = "Search by email address") @RequestParam(required = false) String email,
            @Parameter(description = "Search by phone number") @RequestParam(required = false) String phone,
            @Parameter(description = "Maximum results to return") @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "Starting offset for pagination") @RequestParam(defaultValue = "0") int offset) {

        log.debug("Searching persons: name={}, email={}, phone={}, limit={}, offset={}",
                name, email, phone, limit, offset);

        List<GetPersonResponse> results = personService.searchPersons(name, email, phone, limit, offset);
        return ResponseEntity.ok(results);
    }

    private UUID extractUserId(Principal principal) {
        if (principal == null) {
            return null;
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            log.warn("Unable to parse user ID from principal name: {}", principal.getName());
            return null;
        }
    }
}
