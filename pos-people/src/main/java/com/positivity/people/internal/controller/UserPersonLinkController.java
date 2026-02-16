package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
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
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/people")
@Tag(name = "User-Person Linking API", description = "Link authentication users to person records")
public class UserPersonLinkController {

    private final UserPersonLinkService linkService;

    public UserPersonLinkController(@NonNull UserPersonLinkService linkService) {
        this.linkService = linkService;
    }

    @PostMapping("/users/{userId}/link")
    @EmitEvent(id = "USER_PERSON_LINK_CREATE", apiVersion = "1")
    @Operation(summary = "Link user to person", description = "Create a link between an authentication user and a person record")
    @ApiResponse(responseCode = "201", description = "Link created successfully", content = @Content(schema = @Schema(implementation = UserPersonLinkResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "Person not found")
    @ApiResponse(responseCode = "409", description = "User already linked")
    public ResponseEntity<UserPersonLinkResponse> linkUserToPerson(
            @Parameter(description = "User ID from authentication system", required = true) @PathVariable String userId,
            @Valid @RequestBody LinkUserToPersonRequest request) {

        request.setUserId(userId);

        UserPersonLinkResponse response = linkService.linkUserToPerson(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/users/{userId}/link")
    @EmitEvent(id = "PEOPLE_USER_PERSON_LINK_DELETE", apiVersion = "1")
    @Operation(summary = "Unlink user from person", description = "Remove the link between a user and person")
    @ApiResponse(responseCode = "204", description = "Link deleted successfully")
    @ApiResponse(responseCode = "404", description = "Link not found")
    public ResponseEntity<Void> unlinkUserFromPerson(
            @Parameter(description = "User ID", required = true) @PathVariable String userId) {

        linkService.unlinkUserFromPerson(userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{userId}/person")
    @Operation(summary = "Get person by user ID", description = "Retrieve the person record linked to a user")
    @ApiResponse(responseCode = "200", description = "Person found", content = @Content(schema = @Schema(implementation = PersonResponse.class)))
    @ApiResponse(responseCode = "404", description = "Link or person not found")
    public ResponseEntity<PersonResponse> getPersonByUserId(
            @Parameter(description = "User ID", required = true) @PathVariable String userId) {

        PersonResponse person = linkService.findPersonByUserId(userId);
        return ResponseEntity.ok(person);
    }

    @GetMapping("/{personId}/users")
    @Operation(summary = "Get users linked to person", description = "Retrieve all user IDs linked to a person record")
    @ApiResponse(responseCode = "200", description = "User IDs returned")
    @ApiResponse(responseCode = "404", description = "Person not found")
    public ResponseEntity<List<String>> getUserIdsByPersonId(
            @Parameter(description = "Person ID", required = true) @PathVariable UUID personId) {

        List<String> userIds = linkService.findUserIdsByPersonId(personId);
        return ResponseEntity.ok(userIds);
    }
}
