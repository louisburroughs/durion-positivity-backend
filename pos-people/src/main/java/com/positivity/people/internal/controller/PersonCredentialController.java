package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.PersonCredentialResponse;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.PersonCredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The credentials a person holds (CAP-328) — the read side of the aggregate pos-people owns. */
@RestController
@RequestMapping("/v1/people/{personId}/credentials")
@RequiredArgsConstructor
@Tag(name = "Person Credential API", description = "The credentials a person holds or held")
public class PersonCredentialController {

    private final PersonCredentialService personCredentialService;

    @GetMapping
    @EmitEvent(id = "PEOPLE_CREDENTIAL_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.EMPLOYEE_VIEW + "')")
    @Operation(operationId = "listPersonCredentials", summary = "List a Person's Credentials", description = """
                    Returns every credential the person holds or held, newest issue first, each with the registry \
                    skill it certifies and its status as of today: ACTIVE or EXPIRED from the dates, REVOKED or \
                    SUPERSEDED when set deliberately. A renewal appears as its own row beside the one it renewed, \
                    so a past date's qualification stays visible.
                    Use this tool to see what a technician is certified to do, or to audit an inspector's \
                    qualification on a past date; for a shop's roster use shop management's technician roster \
                    instead, which reads a replica of the same credentials.
                    Preconditions: the caller holds people:employee:view.
                    Required inputs: personId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the list, empty when the person holds none, and 403 without the authority.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "The person's credentials.",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = PersonCredentialResponse.class))))
    public ResponseEntity<List<PersonCredentialResponse>> listCredentials(
            @Parameter(description = "Person id", required = true) @PathVariable UUID personId) {
        return ResponseEntity.ok(personCredentialService.listByPerson(personId));
    }
}
