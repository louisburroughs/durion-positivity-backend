package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.PeopleAvailabilityResponse;
import com.positivity.people.internal.dto.PrimaryLocationResponse;
import com.positivity.people.internal.dto.StaffingAssignmentResponse;
import com.positivity.people.service.PeopleAvailabilityService;
import com.positivity.people.service.StaffingAssignmentService;
import com.positivity.people.service.UserPersonTranslationService;
import com.positivity.security.common.SecurityContextHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"people:availability:view"})
@RequestMapping("/v1/people")
@RequiredArgsConstructor
public class PeopleAvailabilityController {

    private final PeopleAvailabilityService peopleAvailabilityService;

    private final StaffingAssignmentService staffingAssignmentService;

    private final UserPersonTranslationService userPersonTranslationService;

    @Operation(
            summary = "Get people availability",
            description = "Return availability with optional locationId and date filters.")
    @ApiResponse(responseCode = "200", description = "Availability data returned successfully.")
    @GetMapping("/availability")
    @EmitEvent(id = "PEOPLE_AVAILABILITY_LIST", apiVersion = "1")
    @PreAuthorize("hasAuthority('people:availability:view')")
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

    @Operation(
            summary = "Get current user's primary location",
            description = "Resolve the authenticated user's primary active location from their staffing assignments. "
                    + "Returns 404 when the user has no active assignment flagged as primary.")
    @ApiResponse(responseCode = "200", description = "Primary location resolved successfully.")
    @ApiResponse(responseCode = "404", description = "No primary location assignment found for current user.")
    @GetMapping("/me/primary-location")
    @EmitEvent(id = "PEOPLE_PRIMARY_LOCATION_GET", apiVersion = "1")
    @PreAuthorize("hasAuthority('people:availability:view')")
    public ResponseEntity<PrimaryLocationResponse> getCurrentUserPrimaryLocation() {
        UUID locationId = peopleAvailabilityService.resolveCurrentUserPrimaryLocationId();
        log.info("Resolved primary location(mask) {} for current user", maskForLog(locationId));
        return ResponseEntity.ok(
                PrimaryLocationResponse.builder().locationId(locationId).build());
    }

    @Operation(
            summary = "Get current user's active location assignments",
            description = "List the authenticated user's staffing assignments that are active today, primary first."
                    + " Returns an empty list when the user has no current location.")
    @ApiResponse(responseCode = "200", description = "Active location assignments returned successfully.")
    @ApiResponse(responseCode = "404", description = "No person linked to the current user.")
    @GetMapping("/me/locations")
    @EmitEvent(id = "PEOPLE_ME_LOCATIONS_LIST", apiVersion = "1")
    @PreAuthorize("hasAuthority('people:availability:view')")
    public List<StaffingAssignmentResponse> getCurrentUserLocations() {
        return staffingAssignmentService.findActiveByPersonId(resolveCurrentPersonId());
    }

    @Operation(
            summary = "Get a person's active location assignments",
            description = "List a person's staffing assignments that are active today, primary first. Returns an"
                    + " empty list when the person has no current location.")
    @ApiResponse(responseCode = "200", description = "Active location assignments returned successfully.")
    @GetMapping("/{personId}/locations")
    @EmitEvent(id = "PEOPLE_PERSON_LOCATIONS_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:view"})
    @PreAuthorize("hasAuthority('people:employee:view')")
    public List<StaffingAssignmentResponse> getPersonLocations(
            @Parameter(description = "Person id") @PathVariable UUID personId) {
        return staffingAssignmentService.findActiveByPersonId(personId);
    }

    @Operation(
            summary = "Get a person's primary location",
            description = "Resolve a person's primary active location from their staffing assignments. Returns 404"
                    + " when the person has no active assignment flagged as primary.")
    @ApiResponse(responseCode = "200", description = "Primary location resolved successfully.")
    @ApiResponse(responseCode = "404", description = "No primary location assignment found for the person.")
    @GetMapping("/{personId}/primary-location")
    @EmitEvent(id = "PEOPLE_PERSON_PRIMARY_LOCATION_GET", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:view"})
    @PreAuthorize("hasAuthority('people:employee:view')")
    public ResponseEntity<PrimaryLocationResponse> getPersonPrimaryLocation(
            @Parameter(description = "Person id") @PathVariable UUID personId) {
        UUID locationId = peopleAvailabilityService.resolvePrimaryLocationId(personId);
        return ResponseEntity.ok(
                PrimaryLocationResponse.builder().locationId(locationId).build());
    }

    private UUID resolveCurrentPersonId() {
        String username = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new EntityNotFoundException("Authenticated user context is missing"));
        return userPersonTranslationService.getPersonUuidForUser(username);
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
