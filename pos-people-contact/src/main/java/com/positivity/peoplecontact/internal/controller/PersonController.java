package com.positivity.peoplecontact.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.peoplecontact.internal.dto.Person;
import com.positivity.peoplecontact.internal.dto.ResolvePersonRequest;
import com.positivity.peoplecontact.internal.dto.ResolvePersonResponse;
import com.positivity.peoplecontact.internal.security.PeopleContactPermissions;
import com.positivity.peoplecontact.service.PersonService;
import com.positivity.peoplecontact.service.UserPersonTranslationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
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

    private final UserPersonTranslationService userPersonTranslationService;

    @Operation(operationId = "getCurrentPerson", summary = "Get Current User's Person Record", description = """
                    Resolves the authenticated caller's username to its linked person record and returns the \
                    identity snapshot with emails, work phone numbers and username.
                    Use this tool when the caller needs their own person record without knowing a person id; do \
                    not use getPersonById, which requires a known person UUID and can read any person.
                    Preconditions: an active user-person link must exist for the authenticated username, and the \
                    linked person record must still exist.
                    Required inputs: none; identity comes entirely from the authenticated security context.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when the caller has no user-person link or the linked person no longer exists, \
                    and 401 when the authenticated context carries no username.
                    """)
    @ApiResponse(responseCode = "200", description = "Person found and returned.")
    @ApiResponse(
            responseCode = "404",
            description = "No person linked to the current user.",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/me")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:person:view"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.PERSON_VIEW + "')")
    public Person getCurrentPerson() {
        UUID personId = userPersonTranslationService.getPersonUuidForCurrentUser();
        return personService
                .getPersonById(personId)
                .orElseThrow(() -> new EntityNotFoundException("No person found for id: " + personId));
    }

    @Operation(operationId = "listPeople", summary = "List People in Identity Directory", description = """
                    Lists person records in the identity directory, optionally narrowed by a free-text filter, \
                    with each result carrying emails, work phone numbers and any linked username.
                    Use this tool when browsing or searching people by name or email; do not use resolvePerson, \
                    which performs weighted duplicate matching and may create a new person as a side effect.
                    Preconditions: none; an empty directory simply yields an empty list.
                    Required inputs: none; q is an optional case-insensitive filter matched against firstName, \
                    lastName and primaryEmail, and employment attributes cannot be filtered here because they \
                    live in pos-people (ADR-0044 §6).
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the matching people, possibly an empty list when nothing matches the filter.
                    """)
    @Parameter(name = "q", description = "Case-insensitive text search on firstName, lastName, primaryEmail.")
    @ApiResponse(responseCode = "200", description = "List of people returned successfully.")
    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:person:view"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.PERSON_VIEW + "')")
    public List<Person> getAllPeople(@RequestParam(name = "q", required = false) String q) {
        return personService.getAllPeople(q);
    }

    @Operation(operationId = "getPersonById", summary = "Get a Person by Unique ID", description = """
                    Returns a single person record by its UUID, including emails, work phone numbers and any \
                    linked username.
                    Use this tool when the person id is already known; use listPeople instead when searching by \
                    name or email, and getCurrentPerson when the target is the authenticated caller.
                    Preconditions: the person record must exist in the identity directory.
                    Required inputs: personId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no person exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Person found and returned.")
    @ApiResponse(
            responseCode = "404",
            description = "Person not found.",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{personId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:person:view"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.PERSON_VIEW + "')")
    public ResponseEntity<Person> getPersonById(
            @Parameter(description = "ID of the person to retrieve", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID personId) {
        return personService
                .getPersonById(personId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(operationId = "getPeopleByIds", summary = "Batch Resolve People by IDs", description = """
                    Batch-resolves person records by id in one request, attaching typed contact points and linked \
                    usernames to each result.
                    Use this tool for cross-service identity reads that need several people at once (ADR-0015); \
                    use getPersonById instead for a single known id.
                    Preconditions: none; unknown ids are silently omitted, so the result may be smaller than the \
                    request.
                    Required inputs: a JSON array of person UUIDs as the request body; an empty array yields an \
                    empty list.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the resolved people; ids that do not exist are dropped rather than reported \
                    as errors.
                    """)
    @ApiResponse(responseCode = "200", description = "Persons resolved.")
    @PostMapping("/by-ids")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:person:view"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.PERSON_VIEW + "')")
    public List<Person> getPeopleByIds(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "JSON array of person UUIDs to resolve in one batch.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Two ids", value = """
                                                                    ["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"]
                                                                    """)))
                    @RequestBody
                    List<UUID> ids) {
        return personService.getPeopleByIds(ids);
    }

    @Operation(operationId = "replaceContactPoints", summary = "Replace a Person's Contact Points", description = """
                    Replaces the full set of typed contact points for one person; pos-people-contact is the \
                    source of truth for contact channels (ADR-0015).
                    Use this tool to overwrite a person's contact channels wholesale; do not use updatePerson, \
                    which replaces names, primaryEmail, secondaryEmail and phoneNumbers as flat identity fields \
                    rather than the typed contact-point set.
                    Preconditions: the person should exist; the id is not validated here, so writes for an \
                    unknown person are stored without error.
                    Required inputs: a JSON array of contact points, each with contactType (EMAIL, PHONE_MOBILE, \
                    PHONE_HOME or PHONE_WORK), value, and primary; entries missing contactType or value are \
                    silently dropped, and an empty array clears all contact points.
                    Emits a people-contact person.updated identity fact through the transactional outbox when \
                    the person exists; prior contact points are deleted in the same transaction.
                    Returns 204 on success, including when the array is empty or the person id is unknown.
                    """)
    @ApiResponse(responseCode = "204", description = "Contact points replaced.")
    @PutMapping("/{personId}/contact-points")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:person:edit"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.PERSON_EDIT + "')")
    public ResponseEntity<Void> replaceContactPoints(
            @Parameter(description = "Person id") @PathVariable UUID personId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Complete replacement set of typed contact points for the person.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Email and mobile phone", value = """
                                                                    [{"contactType":"EMAIL",
                                                                      "value":"jane.smith@example.com",
                                                                      "primary":true},
                                                                     {"contactType":"PHONE_MOBILE",
                                                                      "value":"+15551234567",
                                                                      "primary":true}]
                                                                    """)))
                    @RequestBody
                    List<Person.ContactPointDto> contactPoints) {
        personService.replaceContactPoints(personId, contactPoints);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "createPerson", summary = "Create a New Person Record", description = """
                    Creates a new person identity record; emails and phone numbers are persisted as contact \
                    points owned by this module.
                    Use this tool when the person is known to be new; do not use resolvePerson, which should be \
                    preferred when a matching person may already exist, and do not use linkUserToPerson, which \
                    only associates an existing person with a security user.
                    Preconditions: none; duplicate names or emails are not rejected, so deduplication is the \
                    caller's responsibility (or use resolvePerson).
                    Required inputs: firstName and lastName (non-blank); primaryEmail, secondaryEmail and \
                    phoneNumbers are optional, and username is ignored because usernames are owned by \
                    pos-security and linked separately.
                    Emits a PEOPLE_CONTACT_PERSON_CREATE event and queues a person.updated identity fact on the \
                    transactional outbox.
                    Returns 201 with the persisted person, and 400 when firstName or lastName is blank.
                    """)
    @ApiResponse(responseCode = "201", description = "Person created successfully.")
    @EmitEvent(id = "PEOPLE_CONTACT_PERSON_CREATE", apiVersion = "1")
    @PostMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:person:create"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.PERSON_CREATE + "')")
    public ResponseEntity<Person> createPerson(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Identity fields of the person to create; contact data is stored as"
                                    + " contact points.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "New person", value = """
                                                                    {"firstName":"Jane",
                                                                     "lastName":"Smith",
                                                                     "primaryEmail":"jane.smith@example.com",
                                                                     "phoneNumbers":["+15551234567"]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    Person person) {
        Person saved = personService.savePerson(person);
        return ResponseEntity.status(201).body(saved);
    }

    @Operation(operationId = "resolvePerson", summary = "Resolve or Create Matching Person", description = """
                    Finds the best-matching existing person using weighted scoring (email 60, phone 25, lastName \
                    10, firstName 5) and creates a new person when no candidate reaches the threshold.
                    Use this tool to deduplicate at intake before creating people; do not use createPerson, \
                    which always inserts a new record without any matching.
                    Preconditions: none; inputs are normalized before scoring (email lowercased, phone reduced \
                    to digits, names trimmed).
                    Required inputs: at least one of email, phone, lastName or firstName; threshold is optional \
                    and defaults to the configured pos.people-contact.matching.threshold value (30 unless \
                    overridden).
                    Emits a PEOPLE_CONTACT_PERSON_RESOLVE event; when no match reaches the threshold a new \
                    person is created and a person.updated identity fact is queued on the outbox.
                    Returns 200 with matchedExisting true, the score and the matchedBy reasons when a candidate \
                    wins, 200 with matchedExisting false when a new person was created, and 400 when all four \
                    matching inputs are absent or blank.
                    """)
    @ApiResponse(responseCode = "200", description = "Person resolved successfully.")
    @EmitEvent(id = "PEOPLE_CONTACT_PERSON_RESOLVE", apiVersion = "1")
    @PostMapping("/resolve")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:person:create"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.PERSON_CREATE + "')")
    public ResponseEntity<ResolvePersonResponse> resolvePerson(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Weighted matching criteria used to find or create the person.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Match by email and name", value = """
                                                                    {"email":"jane.smith@example.com",
                                                                     "phone":"+15551234567",
                                                                     "lastName":"Smith",
                                                                     "firstName":"Jane",
                                                                     "threshold":30}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ResolvePersonRequest request) {
        ResolvePersonResponse resolved = personService.resolvePerson(request);
        return ResponseEntity.ok(resolved);
    }

    @Operation(operationId = "updatePerson", summary = "Update an Existing Person Record", description = """
                    Updates an existing person's identity fields, fully replacing names, emails and work phone \
                    numbers with the submitted values.
                    Use this tool to correct or complete a known person's identity data; do not use \
                    replaceContactPoints, which manages the typed contact-point set, and do not use createPerson \
                    for records that do not exist yet.
                    Preconditions: the person record must already exist for the supplied id.
                    Required inputs: personId (UUID) as a path parameter plus a full person body with non-blank \
                    firstName and lastName; omitting primaryEmail, secondaryEmail or phoneNumbers erases the \
                    stored values, and username is ignored because it is owned by pos-security.
                    Emits a PEOPLE_CONTACT_PERSON_UPDATE event and queues a person.updated identity fact on the \
                    transactional outbox.
                    Returns 200 with the updated person, 404 when no person exists for the id, and 400 when \
                    firstName or lastName is blank.
                    """)
    @ApiResponse(responseCode = "200", description = "Person updated successfully.")
    @ApiResponse(
            responseCode = "404",
            description = "Person not found.",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @EmitEvent(id = "PEOPLE_CONTACT_PERSON_UPDATE", apiVersion = "1")
    @PutMapping("/{personId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:person:edit"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.PERSON_EDIT + "')")
    public ResponseEntity<Person> updatePerson(
            @Parameter(description = "ID of the person to update", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID personId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Full replacement identity snapshot for the person.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Updated person", value = """
                                                                    {"firstName":"Jane",
                                                                     "lastName":"Smith-Jones",
                                                                     "primaryEmail":"jane.smith@example.com",
                                                                     "secondaryEmail":"jane.alt@example.com",
                                                                     "phoneNumbers":["+15551234567"]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    Person person) {
        if (personService.getPersonById(personId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        person.setId(personId);
        Person updated = personService.savePerson(person);
        return ResponseEntity.ok(updated);
    }

    @Operation(operationId = "deletePerson", summary = "Delete a Person Identity Record", description = """
                    Deletes a person record and, in the same transaction, its contact points and postal address.
                    Use this tool to permanently remove an identity record; do not use unlinkUserFromPerson, \
                    which only removes a user link and leaves the person in place.
                    Preconditions: the person must exist and must have no user-person links; linked users must \
                    be removed first with unlinkUserFromPerson.
                    Required inputs: personId (UUID) as a path parameter; there is no request body.
                    Emits a PEOPLE_CONTACT_PERSON_DELETE event and queues a person.deleted fact on the \
                    transactional outbox.
                    Returns 204 on success, 404 when the person does not exist, and 409 when one or more users \
                    are still linked to the person.
                    """)
    @ApiResponse(responseCode = "204", description = "Person deleted successfully.")
    @ApiResponse(
            responseCode = "404",
            description = "Person not found.",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Person still has linked users; unlink them first.",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @EmitEvent(id = "PEOPLE_CONTACT_PERSON_DELETE", apiVersion = "1")
    @DeleteMapping("/{personId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:person:delete"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.PERSON_DELETE + "')")
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
