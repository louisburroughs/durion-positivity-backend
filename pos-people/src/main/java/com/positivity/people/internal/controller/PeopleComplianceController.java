package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.InactivePersonActiveUserResponse;
import com.positivity.people.service.PeopleComplianceService;
import io.swagger.v3.oas.annotations.Operation;
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
            summary = "List active users linked to inactive persons",
            description = "Compliance check (ADR-0015 §4): returns every ACTIVE user-person link whose linked person"
                    + " is in an inactive employment status (SUSPENDED, TERMINATED, DISABLED). Each row is a user"
                    + " that should have been disabled when the person was disabled/archived. Returns an empty list"
                    + " when none. Link facts come from the people-contact replica, so results can trail the link"
                    + " authority by the event-propagation delay.")
    @ApiResponse(
            responseCode = "200",
            description = "Offending links returned (possibly empty)",
            content = @Content(schema = @Schema(implementation = InactivePersonActiveUserResponse.class)))
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
