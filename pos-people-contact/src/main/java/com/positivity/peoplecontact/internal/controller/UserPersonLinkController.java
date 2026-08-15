package com.positivity.peoplecontact.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.peoplecontact.internal.dto.CreateUserLinkRequest;
import com.positivity.peoplecontact.internal.dto.LinkUserToPersonRequest;
import com.positivity.peoplecontact.internal.dto.PersonResponse;
import com.positivity.peoplecontact.internal.dto.UserPersonLinkResponse;
import com.positivity.peoplecontact.internal.security.PeopleContactPermissions;
import com.positivity.peoplecontact.service.UserPersonLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/people")
@Tag(name = "User-Person Linking API", description = "Link authentication users to person records")
public class UserPersonLinkController {

    private final UserPersonLinkService linkService;

    @PostMapping("/users/link")
    @EmitEvent(id = "PEOPLE_CONTACT_USER_LINK_CREATE", apiVersion = "1")
    @Operation(operationId = "linkUserToPerson", summary = "Link User to Person Record", description = """
                    Links a security user account to a person record, optionally recording a linkType and notes; \
                    the link is what lets a login act as that person.
                    Use this tool when associating a user with a person, for example during onboarding; do not \
                    use createUserPersonLink, which is the minimal admin variant without linkType or notes, and \
                    do not use createPerson, which creates the identity record itself.
                    Preconditions: the person must exist, and the username must not already be linked to a \
                    different person; one username maps to at most one person.
                    Required inputs: username and personId (UUID); linkType defaults to PRIMARY and notes is \
                    optional.
                    Emits a PEOPLE_CONTACT_USER_LINK_CREATE event and queues a link-updated fact on the \
                    transactional outbox.
                    Returns 201 when the link is created, 200 when the identical link already exists (the call \
                    is idempotent), 404 when the person does not exist, and 409 when the username is already \
                    linked to a different person.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Link created successfully",
            content = @Content(schema = @Schema(implementation = UserPersonLinkResponse.class)))
    @ApiResponse(
            responseCode = "200",
            description = "Link already exists and is returned",
            content = @Content(schema = @Schema(implementation = UserPersonLinkResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "Person not found")
    @ApiResponse(responseCode = "409", description = "User already linked to different person")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:userLink:write"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.USERLINK_WRITE + "')")
    public ResponseEntity<UserPersonLinkResponse> linkUserToPerson(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "User-person pair to link, with optional link classification and notes.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Onboarding link", value = """
                                                                    {"username":"marcus.webb",
                                                                     "personId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "linkType":"PRIMARY",
                                                                     "notes":"Linked during onboarding"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    LinkUserToPersonRequest request) {

        boolean alreadyLinked =
                linkService.linkExistsByUsernameAndPersonId(request.getUsername(), request.getPersonId());

        UserPersonLinkResponse response = linkService.linkUserToPerson(request);
        return alreadyLinked
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/user-links")
    @EmitEvent(id = "PEOPLE_CONTACT_USER_LINK_CREATE", apiVersion = "1")
    @Operation(operationId = "createUserPersonLink", summary = "Create User Person Link as Admin", description = """
                    Creates a user-person link from the minimal administrative payload of username and personId \
                    only; the link is stored with linkType PRIMARY.
                    Use this tool for administrative linking when linkType and notes are not needed; do not use \
                    linkUserToPerson, which accepts the same pair plus an explicit linkType and notes.
                    Preconditions: the person must exist, and the username must not already be linked to a \
                    different person; one username maps to at most one person.
                    Required inputs: username and personId (UUID); both are mandatory and there are no other \
                    fields.
                    Emits a PEOPLE_CONTACT_USER_LINK_CREATE event and queues a link-updated fact on the \
                    transactional outbox.
                    Returns 201 when the link is created, 200 when the identical link already exists (the call \
                    is idempotent), 404 when the person does not exist, and 409 when the username is already \
                    linked to a different person.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Link created successfully",
            content = @Content(schema = @Schema(implementation = UserPersonLinkResponse.class)))
    @ApiResponse(
            responseCode = "200",
            description = "Link already exists and is returned",
            content = @Content(schema = @Schema(implementation = UserPersonLinkResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "Person not found")
    @ApiResponse(responseCode = "409", description = "User already linked to different person")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:userLink:write"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.USERLINK_WRITE + "')")
    public ResponseEntity<UserPersonLinkResponse> createUserPersonLink(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Minimal user-person pair to link; the link is stored as PRIMARY.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Admin link", value = """
                                                                    {"username":"marcus.webb",
                                                                     "personId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateUserLinkRequest request) {
        boolean alreadyLinked =
                linkService.linkExistsByUsernameAndPersonId(request.getUsername(), request.getPersonId());
        UserPersonLinkResponse response = linkService.createUserLink(request.getUsername(), request.getPersonId());
        return alreadyLinked
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/users/{username}/link")
    @EmitEvent(id = "PEOPLE_CONTACT_USER_PERSON_LINK_DELETE", apiVersion = "1")
    @Operation(operationId = "unlinkUserFromPerson", summary = "Unlink User from Person Record", description = """
                    Removes the link between a security user and its person record, leaving both the user \
                    account and the person untouched.
                    Use this tool to sever a login from an identity, including before deletePerson on a person \
                    that still has linked users; do not use revokePersonRoleAssignment, which removes a single role \
                    rather than the whole link.
                    Preconditions: a user-person link must exist for the username.
                    Required inputs: username as a path parameter; there is no request body.
                    Emits a PEOPLE_CONTACT_USER_PERSON_LINK_DELETE event and queues a link-removed fact on the \
                    transactional outbox.
                    Returns 204 on success, and 404 when no link exists for the username.
                    """)
    @ApiResponse(responseCode = "204", description = "Link deleted successfully")
    @ApiResponse(responseCode = "404", description = "Link not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:userLink:write"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.USERLINK_WRITE + "')")
    public ResponseEntity<Void> unlinkUserFromPerson(
            @Parameter(description = "Username", required = true) @PathVariable String username) {

        linkService.unlinkUserFromPerson(username);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{username}/person")
    @Operation(operationId = "getPersonByUsername", summary = "Get Person Linked to Username", description = """
                    Returns the person record linked to a username, including emails, work phone numbers and the \
                    username itself.
                    Use this tool to translate a login identity into its person record; use getCurrentPerson \
                    instead when the target is the authenticated caller, and getLinksByPersonId to go from a \
                    person to its links.
                    Preconditions: a user-person link must exist for the username, and the linked person record \
                    must still exist.
                    Required inputs: username as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no link exists for the username or the linked person record is missing.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Person found",
            content = @Content(schema = @Schema(implementation = PersonResponse.class)))
    @ApiResponse(responseCode = "404", description = "Link or person not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:userLink:view"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.USERLINK_VIEW + "')")
    public ResponseEntity<PersonResponse> getPersonByUsername(
            @Parameter(description = "Username", required = true) @PathVariable String username) {

        PersonResponse person = linkService.findPersonByUsername(username);
        return ResponseEntity.ok(person);
    }

    @GetMapping("/{personId}/users")
    @Operation(operationId = "listUsernamesForPerson", summary = "List Usernames Linked to Person", description = """
                    Lists every username linked to a person record as a flat array of strings.
                    Use this tool to see which logins can act as a person; use getLinksByPersonId instead when \
                    the full link records with linkType, notes and audit fields are needed.
                    Preconditions: the person record must exist; a person with no links yields an empty list.
                    Required inputs: personId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when the person does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Usernames returned")
    @ApiResponse(responseCode = "404", description = "Person not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:userLink:view"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.USERLINK_VIEW + "')")
    public ResponseEntity<List<String>> getUsernamesByPersonId(
            @Parameter(description = "Person ID", required = true) @PathVariable UUID personId) {

        List<String> usernames = linkService.findUsernamesByPersonId(personId);
        return ResponseEntity.ok(usernames);
    }

    @GetMapping("/user-links/{personId}")
    @EmitEvent(id = "PEOPLE_CONTACT_USER_LINK_GET", apiVersion = "1")
    @Operation(operationId = "getLinksByPersonId", summary = "Get User Links for Person", description = """
                    Returns the full user-person link records for a person, including linkType, notes, creator \
                    and creation time.
                    Use this tool when link metadata is needed; use listUsernamesForPerson instead when only the \
                    usernames matter, and getPersonByUsername to go from a username to its person.
                    Preconditions: the person record must exist; a person with no links yields an empty list.
                    Required inputs: personId (UUID) as a path parameter; there is no request body.
                    Emits a PEOPLE_CONTACT_USER_LINK_GET audit event; no state changes.
                    Returns 404 when the person does not exist.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Links found",
            content = @Content(schema = @Schema(implementation = UserPersonLinkResponse.class)))
    @ApiResponse(responseCode = "404", description = "Link or person not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:userLink:view"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.USERLINK_VIEW + "')")
    public ResponseEntity<List<UserPersonLinkResponse>> getLinksByPersonId(
            @Parameter(description = "Person ID", required = true) @PathVariable UUID personId) {
        return ResponseEntity.ok(linkService.getUserLinks(personId));
    }
}
