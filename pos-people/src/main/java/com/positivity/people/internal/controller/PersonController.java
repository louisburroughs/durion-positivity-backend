package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.Person;
import com.positivity.people.internal.dto.ResolvePersonRequest;
import com.positivity.people.internal.dto.ResolvePersonResponse;
import com.positivity.people.service.PersonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "People API", description = "Operations related to people records")
@RestController
@RequestMapping("/v1/people")
@RequiredArgsConstructor
public class PersonController {

    private final PersonService personService;

    @Operation(summary = "Get all people", description = "Retrieve people with optional type and text filters.")
    @Parameter(
            name = "type",
            description = "Filter by person type: EMPLOYEE, ACTIVE, INACTIVE, or ALL (default).",
            schema = @Schema(allowableValues = {"ALL", "EMPLOYEE", "ACTIVE", "INACTIVE"}))
    @Parameter(name = "q", description = "Case-insensitive text search on firstName, lastName, primaryEmail, username.")
    @ApiResponse(responseCode = "200", description = "List of people returned successfully.")
    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:person:view"})
    @PreAuthorize("hasAuthority('people:person:view')")
    public List<Person> getAllPeople(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "q", required = false) String q) {
        return personService.getAllPeople(type, q);
    }

    @Operation(summary = "Get person by ID", description = "Retrieve a person by their unique ID.")
    @ApiResponse(responseCode = "200", description = "Person found and returned.")
    @ApiResponse(responseCode = "404", description = "Person not found.")
    @GetMapping("/{personId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:person:view"})
    @PreAuthorize("hasAuthority('people:person:view')")
    public ResponseEntity<Person> getPersonById(
            @Parameter(description = "ID of the person to retrieve", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID personId) {
        return personService
                .getPersonById(personId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Get people by IDs",
            description = "Batch-resolve persons by id in one request. Unknown ids are omitted; the result may be"
                    + " smaller than the request. Used for cross-service identity reads (ADR-0015).")
    @ApiResponse(responseCode = "200", description = "Persons resolved.")
    @PostMapping("/by-ids")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:person:view"})
    @PreAuthorize("hasAuthority('people:person:view')")
    public List<Person> getPeopleByIds(@Parameter(description = "Person ids to resolve") @RequestBody List<UUID> ids) {
        return personService.getPeopleByIds(ids);
    }

    @Operation(
            summary = "Replace a person's contact points",
            description = "Replaces all typed contact points (email/phone) for a person. pos-people is the source of"
                    + " truth for contacts (ADR-0015).")
    @ApiResponse(responseCode = "204", description = "Contact points replaced.")
    @PutMapping("/{personId}/contact-points")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:person:edit"})
    @PreAuthorize("hasAuthority('people:person:edit')")
    public ResponseEntity<Void> replaceContactPoints(
            @Parameter(description = "Person id") @PathVariable UUID personId,
            @RequestBody List<Person.ContactPointDto> contactPoints) {
        personService.replaceContactPoints(personId, contactPoints);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Create a new person", description = "Add a new person to the system.")
    @ApiResponse(responseCode = "201", description = "Person created successfully.")
    @EmitEvent(id = "PEOPLE_PERSON_CREATE", apiVersion = "1")
    @PostMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:person:create"})
    @PreAuthorize("hasAuthority('people:person:create')")
    public ResponseEntity<Person> createPerson(
            @Parameter(description = "Person object to be created") @Valid @RequestBody Person person) {
        Person saved = personService.savePerson(person);
        return ResponseEntity.status(201).body(saved);
    }

    @Operation(summary = "Resolve person", description = "Find best matching person by score or create a new person")
    @ApiResponse(responseCode = "200", description = "Person resolved successfully.")
    @EmitEvent(id = "PEOPLE_PERSON_RESOLVE", apiVersion = "1")
    @PostMapping("/resolve")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:person:create"})
    @PreAuthorize("hasAuthority('people:person:create')")
    public ResponseEntity<ResolvePersonResponse> resolvePerson(
            @Parameter(description = "Resolve criteria") @Valid @RequestBody ResolvePersonRequest request) {
        ResolvePersonResponse resolved = personService.resolvePerson(request);
        return ResponseEntity.ok(resolved);
    }

    @Operation(summary = "Update an existing person", description = "Update the details of an existing person.")
    @ApiResponse(responseCode = "200", description = "Person updated successfully.")
    @ApiResponse(responseCode = "404", description = "Person not found.")
    @EmitEvent(id = "PEOPLE_PERSON_UPDATE", apiVersion = "1")
    @PutMapping("/{personId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:person:edit"})
    @PreAuthorize("hasAuthority('people:person:edit')")
    public ResponseEntity<Person> updatePerson(
            @Parameter(description = "ID of the person to update", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID personId,
            @Parameter(description = "Updated person object") @Valid @RequestBody Person person) {
        if (personService.getPersonById(personId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        person.setId(personId);
        Person updated = personService.savePerson(person);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Delete a person", description = "Delete a person by their unique ID.")
    @ApiResponse(responseCode = "204", description = "Person deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Person not found.")
    @EmitEvent(id = "PEOPLE_PERSON_DELETE", apiVersion = "1")
    @DeleteMapping("/{personId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:person:delete"})
    @PreAuthorize("hasAuthority('people:person:delete')")
    public ResponseEntity<Void> deletePerson(
            @Parameter(description = "ID of the person to delete", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID personId) {
        if (personService.getPersonById(personId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        personService.deletePerson(personId);
        return ResponseEntity.noContent().build();
    }
}
