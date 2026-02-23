package com.positivity.location.internal.controller;

import com.positivity.location.internal.dto.EligibleMobileUnitResponse;
import com.positivity.location.service.MobileUnitService;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;

/**
 * Dedicated eligibility endpoint controller for exact route binding.
 *
 * Issue: #76
 */
@RestController
@RequiredArgsConstructor
public class MobileUnitEligibilityController {

    private final MobileUnitService mobileUnitService;

    @Operation(summary = "Find eligible mobile units", description = "Return eligible active units for a service request.")
    @ApiResponse(responseCode = "200", description = "Eligible mobile units returned.")
    @PreAuthorize("hasAuthority('location.mobile-unit.read')")
    @GetMapping("/v1/mobile-units:eligible")
    public ResponseEntity<List<EligibleMobileUnitResponse>> findEligibleMobileUnits(
            @RequestParam String postalCode,
            @RequestParam String countryCode,
            @RequestParam Instant at) {
        return ResponseEntity.ok(mobileUnitService.findEligibleMobileUnits(postalCode, countryCode, at));
    }
}
