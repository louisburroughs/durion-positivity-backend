package com.positivity.people.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Slf4j
@Tag(name = "People Availability API", description = "Operations for querying people availability")
@RestController
@RequestMapping("/v1/people")
@RequiredArgsConstructor
public class PeopleAvailabilityController {

    @Operation(summary = "Get people availability", description = "Return availability with optional locationId and date filters.")
    @ApiResponse(responseCode = "200", description = "Availability data returned successfully.")
    @GetMapping("/availability")
    public ResponseEntity<Object> getPeopleAvailability(
            @Parameter(description = "Filter by location ID") @RequestParam(required = false) Long locationId,
            @Parameter(description = "Filter by date (ISO format: yyyy-MM-dd)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        log.info("Fetching people availability for locationId={}, date={}", locationId, date);
        // TODO: Implement availability query logic
        return ResponseEntity.ok().build();
    }
}
