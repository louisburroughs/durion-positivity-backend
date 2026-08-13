package com.positivity.location.internal.controller;

import com.positivity.location.internal.dto.EligibleMobileUnitResponse;
import com.positivity.location.service.MobileUnitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dedicated eligibility endpoint controller for exact route binding.
 *
 * Issue: #76
 */
@RestController
@SecurityRequirement(
        name = "bearerAuth",
        scopes = {"location:mobile-unit:read"})
@RequiredArgsConstructor
public class MobileUnitEligibilityController {

    private final MobileUnitService mobileUnitService;

    @Operation(
            operationId = "findEligibleMobileUnits",
            summary = "Find Eligible Mobile Units for Address",
            description = """
                    Finds the ACTIVE mobile units whose coverage rules include a postal code on a given date, \
                    ordered by ascending rule priority.
                    Use this tool when dispatching a mobile service request to a customer address; use \
                    listMobileUnits instead for plain enumeration without eligibility matching.
                    Preconditions: coverage rules must already link units to service areas containing the postal \
                    code; units whose status is not ACTIVE are excluded.
                    Required inputs: postalCode, countryCode and at (an ISO-8601 instant), all mandatory; the \
                    instant is reduced to a UTC calendar date for validFrom and validTo matching.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the eligible units, empty when nothing covers the address on that date.
                    """)
    @ApiResponse(responseCode = "200", description = "Eligible mobile units returned.")
    @PreAuthorize("hasAuthority('location:mobile-unit:read')")
    @GetMapping("/v1/mobile-units:eligible")
    public ResponseEntity<List<EligibleMobileUnitResponse>> findEligibleMobileUnits(
            @RequestParam String postalCode, @RequestParam String countryCode, @RequestParam Instant at) {
        return ResponseEntity.ok(mobileUnitService.findEligibleMobileUnits(postalCode, countryCode, at));
    }
}
