package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.PeopleAvailabilityResponse;
import com.positivity.people.service.PeopleAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.positivity.people.internal.dto.PrimaryLocationResponse;

@Slf4j
@Tag(name = "People Availability API", description = "Operations for querying people availability")
@RestController
@RequestMapping("/v1/people")
@RequiredArgsConstructor
public class PeopleAvailabilityController {

    private final PeopleAvailabilityService peopleAvailabilityService;

    @Operation(summary = "Get people availability", description = "Return availability with optional locationId and date filters.")
    @ApiResponse(responseCode = "200", description = "Availability data returned successfully.")
    @GetMapping("/availability")
    @EmitEvent(id = "PEOPLE_AVAILABILITY_LIST", apiVersion = "1")
    @PreAuthorize("hasAuthority('people:availability:view')")
    public ResponseEntity<List<PeopleAvailabilityResponse>> getPeopleAvailability(
            @Parameter(description = "Filter by location ID. Defaults to requester location when omitted.") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Filter by date (ISO format: yyyy-MM-dd)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        log.info("Fetching people availability for locationId={}, date={}", locationId, date);
        return ResponseEntity.ok(peopleAvailabilityService.getPeopleAvailability(locationId, date));
    }

    @Operation(summary = "Get current user's primary location", description = "Resolve the authenticated user's primary active location from their staffing assignments.")
    @ApiResponse(responseCode = "200", description = "Primary location resolved successfully.")
    @ApiResponse(responseCode = "404", description = "No active location assignment found for current user.")
    @GetMapping("/me/primary-location")
    @EmitEvent(id = "PEOPLE_PRIMARY_LOCATION_GET", apiVersion = "1")
    @PreAuthorize("hasAuthority('people:availability:view')")
    public ResponseEntity<PrimaryLocationResponse> getCurrentUserPrimaryLocation() {
        UUID locationId = peopleAvailabilityService.resolveCurrentUserPrimaryLocationId();
        log.info("Resolved primary location {} for current user", locationId);
        return ResponseEntity.ok(PrimaryLocationResponse.builder().locationId(locationId).build());
    }
}
