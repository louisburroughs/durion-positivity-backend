package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.InactivePersonActiveUserResponse;
import com.positivity.people.service.PeopleComplianceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "People Compliance API", description = "Identity-compliance reports (ADR-0015 §4)")
@RestController
@RequestMapping("/v1/people/compliance")
@RequiredArgsConstructor
public class PeopleComplianceController {

    private final PeopleComplianceService complianceService;

    @Operation(
            operationId = "listInactivePersonActiveUsers",
            summary = "List Active Users Linked To Inactive Persons",
            description = """
                    Reports every ACTIVE user-person link whose linked person is in an inactive employment status \
                    (SUSPENDED, TERMINATED, or DISABLED), per ADR-0015 section 4; each row is a user that should \
                    have been disabled during offboarding.
                    Use this tool for periodic identity-compliance audits; do not use disableEmployee here, which \
                    performs the offboarding itself rather than reporting on it.
                    Preconditions: none; link facts come from the people-contact replica, so results can trail the \
                    link authority by the event-propagation delay.
                    Required inputs: none; there are no parameters and no request body.
                    Emits a REPORT_INACTIVE_PERSON_ACTIVE_USER_GENERATED audit event but changes no state; this is a \
                    read-only report.
                    Returns 200 with an empty list when no offending links exist.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Offending links returned (possibly empty)",
            content =
                    @Content(
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = InactivePersonActiveUserResponse.class))))
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @ApiResponse(responseCode = "500", description = "Unexpected server error")
    @EmitEvent(id = "REPORT_INACTIVE_PERSON_ACTIVE_USER_GENERATED", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:compliance:view"})
    @PreAuthorize("hasAuthority('people:compliance:view')")
    @GetMapping("/inactive-person-active-user")
    public ResponseEntity<List<InactivePersonActiveUserResponse>> findActiveUsersForInactivePersons() {
        return ResponseEntity.ok(complianceService.findActiveUsersForInactivePersons());
    }
}
