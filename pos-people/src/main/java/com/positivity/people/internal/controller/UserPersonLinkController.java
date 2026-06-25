package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.CreateUserLinkRequest;
import com.positivity.people.internal.dto.InactivePersonActiveUserResponse;
import com.positivity.people.internal.dto.LinkUserToPersonRequest;
import com.positivity.people.internal.dto.PersonResponse;
import com.positivity.people.internal.dto.UserPersonLinkResponse;
import com.positivity.people.service.UserPersonLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
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
    @EmitEvent(id = "PEOPLE_USER_LINK_CREATE", apiVersion = "1")
    @Operation(
            summary = "Link user to person",
            description = "Create a link between an authentication user and a person record")
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
            scopes = {"people:userLink:write"})
    @PreAuthorize("hasAuthority('people:userLink:write')")
    public ResponseEntity<UserPersonLinkResponse> linkUserToPerson(
            @Valid @RequestBody LinkUserToPersonRequest request) {

        boolean alreadyLinked =
                linkService.linkExistsByUsernameAndPersonId(request.getUsername(), request.getPersonId());

        UserPersonLinkResponse response = linkService.linkUserToPerson(request);
        return alreadyLinked
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/user-links")
    @EmitEvent(id = "PEOPLE_USER_LINK_CREATE", apiVersion = "1")
    @Operation(
            summary = "Create user-person link (admin)",
            description = "Create a link between an authentication user and a person record")
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
            scopes = {"people:userLink:write"})
    @PreAuthorize("hasAuthority('people:userLink:write')")
    public ResponseEntity<UserPersonLinkResponse> createUserPersonLink(
            @Valid @RequestBody CreateUserLinkRequest request) {
        boolean alreadyLinked =
                linkService.linkExistsByUsernameAndPersonId(request.getUsername(), request.getPersonId());
        UserPersonLinkResponse response = linkService.createUserLink(request.getUsername(), request.getPersonId());
        return alreadyLinked
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/users/{username}/link")
    @EmitEvent(id = "PEOPLE_USER_PERSON_LINK_DELETE", apiVersion = "1")
    @Operation(summary = "Unlink user from person", description = "Remove the link between a user and person")
    @ApiResponse(responseCode = "204", description = "Link deleted successfully")
    @ApiResponse(responseCode = "404", description = "Link not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:userLink:write"})
    @PreAuthorize("hasAuthority('people:userLink:write')")
    public ResponseEntity<Void> unlinkUserFromPerson(
            @Parameter(description = "Username", required = true) @PathVariable String username) {

        linkService.unlinkUserFromPerson(username);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{username}/person")
    @Operation(summary = "Get person by username", description = "Retrieve the person record linked to a user")
    @ApiResponse(
            responseCode = "200",
            description = "Person found",
            content = @Content(schema = @Schema(implementation = PersonResponse.class)))
    @ApiResponse(responseCode = "404", description = "Link or person not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:userLink:view"})
    @PreAuthorize("hasAuthority('people:userLink:view')")
    public ResponseEntity<PersonResponse> getPersonByUsername(
            @Parameter(description = "Username", required = true) @PathVariable String username) {

        PersonResponse person = linkService.findPersonByUsername(username);
        return ResponseEntity.ok(person);
    }

    @GetMapping("/{personId}/users")
    @Operation(summary = "Get users linked to person", description = "Retrieve all usernames linked to a person record")
    @ApiResponse(responseCode = "200", description = "Usernames returned")
    @ApiResponse(responseCode = "404", description = "Person not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:userLink:view"})
    @PreAuthorize("hasAuthority('people:userLink:view')")
    public ResponseEntity<List<String>> getUsernamesByPersonId(
            @Parameter(description = "Person ID", required = true) @PathVariable UUID personId) {

        List<String> usernames = linkService.findUsernamesByPersonId(personId);
        return ResponseEntity.ok(usernames);
    }

    @GetMapping("/user-links/inactive-person-active-user")
    @Operation(
            summary = "List active users linked to inactive persons",
            description = "Compliance check (ADR-0015 §4): returns every ACTIVE user-person link whose linked person"
                    + " is in an inactive status (SUSPENDED, TERMINATED, DISABLED). Each row is a user that should"
                    + " have been disabled when the person was disabled/archived. Returns an empty list when none.")
    @ApiResponse(
            responseCode = "200",
            description = "Offending links returned (possibly empty)",
            content = @Content(schema = @Schema(implementation = InactivePersonActiveUserResponse.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:userLink:view"})
    @PreAuthorize("hasAuthority('people:userLink:view')")
    public ResponseEntity<List<InactivePersonActiveUserResponse>> findActiveUsersForInactivePersons() {
        return ResponseEntity.ok(linkService.findActiveUsersForInactivePersons());
    }

    @GetMapping("/user-links/{personId}")
    @EmitEvent(id = "PEOPLE_USER_LINK_GET", apiVersion = "1")
    @Operation(summary = "Get links by person ID", description = "Retrieve user-person links for person")
    @ApiResponse(
            responseCode = "200",
            description = "Links found",
            content = @Content(schema = @Schema(implementation = UserPersonLinkResponse.class)))
    @ApiResponse(responseCode = "404", description = "Link or person not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:userLink:view"})
    @PreAuthorize("hasAuthority('people:userLink:view')")
    public ResponseEntity<List<UserPersonLinkResponse>> getLinkByPersonId(
            @Parameter(description = "Person ID", required = true) @PathVariable UUID personId) {
        return ResponseEntity.ok(linkService.getUserLinks(personId));
    }
}
