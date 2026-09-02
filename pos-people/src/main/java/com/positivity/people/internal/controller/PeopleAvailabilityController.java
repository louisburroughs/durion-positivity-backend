package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.PeopleAvailabilityResponse;
import com.positivity.people.internal.dto.PrimaryLocationResolution;
import com.positivity.people.internal.dto.PrimaryLocationResponse;
import com.positivity.people.internal.dto.StaffingAssignmentResponse;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.PeopleAvailabilityService;
import com.positivity.people.internal.service.StaffingAssignmentService;
import com.positivity.people.internal.service.UserPersonTranslationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "People Availability API", description = "Operations for querying people availability")
@RestController
@RequestMapping("/v1/people")
@RequiredArgsConstructor
public class PeopleAvailabilityController {

    private final PeopleAvailabilityService peopleAvailabilityService;

    private final StaffingAssignmentService staffingAssignmentService;

    private final UserPersonTranslationService userPersonTranslationService;

    @Operation(
            operationId = "listPeopleAvailability",
            summary = "List People Availability For A Location",
            description = """
                    Lists people with staffing assignments active on a given date, joined with identity fields from \
                    the person replica, one row per assignment.
                    Use this tool to see who is available to work at a location on a date; use \
                    getPersonPrimaryLocation instead to resolve a single person's home location.
                    Preconditions: when locationId is omitted the caller must be linked to a person with an active \
                    assignment, because the requester's own location becomes the filter.
                    Required inputs: none are mandatory; locationId (UUID) defaults to the requester's location and \
                    date (yyyy-MM-dd) defaults to today.
                    Emits a PEOPLE_AVAILABILITY_LIST audit event but changes no state; this is a read-only \
                    projection.
                    Returns 404 when locationId is omitted and the requester has no active location assignment or no \
                    person link.
                    """)
    @ApiResponse(responseCode = "200", description = "Availability data returned successfully.")
    @GetMapping("/availability")
    @EmitEvent(id = "PEOPLE_AVAILABILITY_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:availability:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.AVAILABILITY_VIEW + "')")
    public ResponseEntity<List<PeopleAvailabilityResponse>> getPeopleAvailability(
            @Parameter(description = "Filter by location ID. Defaults to requester location when omitted.")
                    @RequestParam(required = false)
                    UUID locationId,
            @Parameter(description = "Filter by date (ISO format: yyyy-MM-dd)")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        log.info("Fetching people availability for locationId(mask)={}, date={}", maskForLog(locationId), date);
        return ResponseEntity.ok(peopleAvailabilityService.getPeopleAvailability(locationId, date));
    }

    @Operation(operationId = "getMyPrimaryLocation", summary = "Get Current User Primary Location", description = """
                    Resolves the authenticated caller's primary active location from their staffing assignments as \
                    of today, defaulting to the platform's top-level location when none is assigned.
                    Use this tool when a UI or service needs the current user's home location; use listMyLocations \
                    instead to see every active assignment, and getPersonPrimaryLocation for a different person.
                    Preconditions: none beyond authentication; callers without a person link or a primary \
                    assignment receive the top-level default location with defaulted=true.
                    Required inputs: none; identity comes from the bearer token and there are no parameters.
                    Emits a PEOPLE_PRIMARY_LOCATION_GET audit event but changes no state; this is a read-only \
                    projection.
                    Returns 404 only when the caller has no primary assignment AND no top-level default location \
                    could be resolved from the location service.
                    """)
    @ApiResponse(responseCode = "200", description = "Primary location resolved successfully.")
    @ApiResponse(
            responseCode = "404",
            description = "No primary location assignment found and no top-level default location available.",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/me/primary-location")
    @EmitEvent(id = "PEOPLE_PRIMARY_LOCATION_GET", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:availability:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.AVAILABILITY_VIEW + "')")
    public ResponseEntity<PrimaryLocationResponse> getCurrentUserPrimaryLocation() {
        PrimaryLocationResolution resolution = peopleAvailabilityService.resolveCurrentUserPrimaryLocation();
        log.info(
                "Resolved primary location(mask) {} for current user (defaulted={})",
                maskForLog(resolution.locationId()),
                resolution.defaulted());
        return ResponseEntity.ok(PrimaryLocationResponse.builder()
                .locationId(resolution.locationId())
                .defaulted(resolution.defaulted())
                .build());
    }

    @Operation(
            operationId = "listMyLocations",
            summary = "List Current User Active Location Assignments",
            description = """
                    Lists the authenticated caller's staffing assignments that are active today, primary first.
                    Use this tool to populate a location switcher for the current user; use getMyPrimaryLocation \
                    instead when only the single primary location is needed.
                    Preconditions: the caller must be linked to a person in the user-link replica; the link data is \
                    event-fed and can lag the link authority.
                    Required inputs: none; identity comes from the bearer token and there are no parameters.
                    Emits a PEOPLE_ME_LOCATIONS_LIST audit event but changes no state; this is a read-only \
                    projection.
                    Returns 200 with an empty list when the person has no assignment active today, 404 when no \
                    person is linked to the current user, and 401 when the security context carries no username.
                    """)
    @ApiResponse(responseCode = "200", description = "Active location assignments returned successfully.")
    @ApiResponse(
            responseCode = "404",
            description = "No person linked to the current user.",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/me/locations")
    @EmitEvent(id = "PEOPLE_ME_LOCATIONS_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:availability:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.AVAILABILITY_VIEW + "')")
    public List<StaffingAssignmentResponse> getCurrentUserLocations() {
        return staffingAssignmentService.findActiveByPersonId(
                userPersonTranslationService.getPersonUuidForCurrentUser());
    }

    @Operation(
            operationId = "listPersonLocations",
            summary = "List Person Active Location Assignments",
            description = """
                    Lists a given person's staffing assignments that are active today, primary first.
                    Use this tool when acting on another person by id; use listMyLocations instead for the \
                    authenticated caller, and listStaffingAssignments for full history including ended assignments.
                    Preconditions: none beyond authentication; an unknown personId is not rejected.
                    Required inputs: personId (UUID) path parameter; there is no request body.
                    Emits a PEOPLE_PERSON_LOCATIONS_LIST audit event but changes no state; this is a read-only \
                    projection.
                    Returns 200 with an empty list when the person has no assignment active today, including when \
                    the personId is unknown.
                    """)
    @ApiResponse(responseCode = "200", description = "Active location assignments returned successfully.")
    @GetMapping("/{personId}/locations")
    @EmitEvent(id = "PEOPLE_PERSON_LOCATIONS_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.EMPLOYEE_VIEW + "')")
    public List<StaffingAssignmentResponse> getPersonLocations(
            @Parameter(description = "Person id") @PathVariable UUID personId) {
        return staffingAssignmentService.findActiveByPersonId(personId);
    }

    @Operation(
            operationId = "getPersonPrimaryLocation",
            summary = "Get Person Primary Location Assignment",
            description = """
                    Resolves a person's primary active location from their staffing assignments as of today.
                    Use this tool for service-to-service location resolution by person id; use getMyPrimaryLocation \
                    instead for the authenticated caller.
                    Preconditions: the person must have an ACTIVE staffing assignment flagged primary whose effective \
                    dates cover today.
                    Required inputs: personId (UUID) path parameter; there is no request body.
                    Emits a PEOPLE_PERSON_PRIMARY_LOCATION_GET audit event but changes no state; this is a read-only \
                    projection.
                    Returns 404 when the person has no active assignment flagged as primary today.
                    """)
    @ApiResponse(responseCode = "200", description = "Primary location resolved successfully.")
    @ApiResponse(
            responseCode = "404",
            description = "No primary location assignment found for the person.",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{personId}/primary-location")
    @EmitEvent(id = "PEOPLE_PERSON_PRIMARY_LOCATION_GET", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.EMPLOYEE_VIEW + "')")
    public ResponseEntity<PrimaryLocationResponse> getPersonPrimaryLocation(
            @Parameter(description = "Person id") @PathVariable UUID personId) {
        UUID locationId = peopleAvailabilityService.resolvePrimaryLocationId(personId);
        return ResponseEntity.ok(
                PrimaryLocationResponse.builder().locationId(locationId).build());
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }
}
